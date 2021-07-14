/*
 * Copyright (c) 2021 Abex
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

#include <algorithm>
#include <atomic>
#include <cassert>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <thread>

#include "absl/container/flat_hash_map.h"
#include "jvmti.h"
#include "zstd.h"
#include "zstd_errors.h"

#define PROF_ERROR(v) (v * 1000000)
#define PROF_ERR_NOJVMTI PROF_ERROR(1)
#define PROF_ERR_ALREADY_RUNNING PROF_ERROR(2)
#define PROF_ERR_NOT_RUNNING PROF_ERROR(3)
#define PROF_ERR_BUFFER_FULL PROF_ERROR(4)
#define PROF_ERR_COMPRESS PROF_ERROR(5)
#define PROF_ERR_NO_METHOD PROF_ERROR(6)

#define PROF_EV_NULL 0
#define PROF_EV_GC 1

jvmtiEnv *jvmti = nullptr;
class Profile;
Profile *active_profile;

jclass profiler_klass;
jmethodID get_heap_info;

class Method {
 public:
	uint32_t id;
	uint32_t samples;
	Method(uint32_t *last_id) : id(++*last_id), samples(0) {}
};

template <class T>
jvmtiError jvmtiFree(T *value) {
	return jvmti->Deallocate((unsigned char *)value);
}

class ZStdCompressor {
 public:
	ZSTD_outBuffer out;
	std::vector<uint8_t> data;
	ZSTD_CStream *cstream;
	size_t last_error;

	ZStdCompressor(size_t min_size)
		: out({nullptr, 0, 0}),
			data(min_size),
			cstream(ZSTD_createCStream()),
			last_error(0) {
		resize();
	}
	~ZStdCompressor() {
		ZSTD_freeCStream(cstream);
	}

	void resize() {
		this->out.dst = &this->data[0];
		this->out.size = this->data.size();
	}

	size_t push(void *raw, size_t len) {
		size_t max_bound = std::max(ZSTD_CStreamOutSize(), ZSTD_compressBound(len));
		if (this->out.pos + max_bound > this->out.size) {
			this->data.resize(this->data.size() + max_bound);
			resize();
		}
		ZSTD_inBuffer inBuf = {
			raw,
			len,
			0};
		for (; inBuf.pos < inBuf.size;) {
			auto err = ZSTD_compressStream2(cstream, &out, &inBuf, ZSTD_e_continue);
			if (ZSTD_isError(err)) {
				if (ZSTD_getErrorCode(err) == ZSTD_error_dstSize_tooSmall) {
					this->data.resize(this->data.size() + max_bound);
					resize();
					continue;
				}
				if (last_error != 0) {
					last_error = err;
				}
				return err;
			}
		}

		return last_error;
	}

	size_t end() {
		for (;;) {
			auto err = ZSTD_endStream(cstream, &out);
			if (err == 0) {
				break;
			}
			if (ZSTD_isError(err)) {
				if (ZSTD_getErrorCode(err) == ZSTD_error_dstSize_tooSmall) {
					this->data.resize(this->data.size() + ZSTD_CStreamOutSize());
					resize();
					continue;
				}
				if (last_error != 0) {
					last_error = err;
				}
				return err;
			}
		}
		return last_error;
	}
};

void JNICALL gc_start(jvmtiEnv *_jvmti);
void JNICALL gc_finish(jvmtiEnv *_jvmti);

class Profile {
 public:
	jint thread_count;
	jthread *thread_list;

	jlong num_samples;

	std::atomic<bool> running;
	std::mutex wait_done;

	ZStdCompressor samples;
	ZStdCompressor header;

	size_t sample_buffer_size;
	uint32_t *sample_buffer;

	absl::Duration sample_rate;

	absl::flat_hash_map<jmethodID, Method> methods;
	uint32_t last_method_id;

	absl::Time start_time;
	absl::Time stop_time;

	std::mutex event_buffer_mutex;
	std::vector<uint32_t> event_buffer;
	absl::Time event_buffer_start;

	absl::Time last_gc_start;

	Profile(jint thread_count, jthread *thread_list, jint sample_buffer_bytes, jint sample_rate_us)
		: thread_count(thread_count),
			thread_list(thread_list),
			num_samples(0),
			running(true),
			wait_done(),
			samples(sample_buffer_bytes),
			header(0x1000),
			sample_buffer_size(0x100FF * sizeof(*sample_buffer)),
			sample_buffer(new uint32_t[sample_buffer_size / sizeof(*sample_buffer)]),
			sample_rate(sample_rate_us * absl::Microseconds(1)),
			methods(),
			last_method_id(0),
			event_buffer_mutex(),
			event_buffer(),
			event_buffer_start(absl::Now()),
			last_gc_start() {
		this->event_buffer.reserve(0x1000);
	}

	~Profile() {
		delete[] thread_list;
		delete[] sample_buffer;
	}

	jint sample(JNIEnv *env, absl::Duration duration) {
		jvmtiStackInfo *stack_info;
		auto err = jvmti->GetThreadListStackTraces(
			thread_count, thread_list,
			/*max_frame_count*/ 0xFFFF, &stack_info);
		if (err != JVMTI_ERROR_NONE) {
			return err;
		}

		size_t sample_offset = 0;
		sample_buffer[sample_offset++] = (uint32_t)(duration / absl::Nanoseconds(1));

		{
			jlongArray heapinfo = (jlongArray)env->CallStaticObjectMethod(profiler_klass, get_heap_info);
			size_t len = env->GetArrayLength(heapinfo);
			size_t len_bytes = len * 8;
			env->GetLongArrayRegion(heapinfo, 0, len, reinterpret_cast<jlong *>(&sample_buffer[sample_offset]));
			sample_offset += len_bytes / sizeof(sample_buffer[0]);
			env->DeleteLocalRef(heapinfo);
		}

		for (auto thread = 0; thread < thread_count; thread++) {
			jvmtiStackInfo *info = &stack_info[thread];
			int frame_count = info->frame_count;
			if (frame_count <= 0) {
				continue;
			}

			if (samples.out.pos >= samples.out.size - sample_buffer_size) {
				return PROF_ERR_BUFFER_FULL;
			}

			sample_buffer[sample_offset++] = info->state;
			sample_buffer[sample_offset++] = frame_count;
			for (int i = 0; i < frame_count; i++) {
				auto m = &methods.try_emplace(info->frame_buffer[i].method, &last_method_id).first->second;
				sample_buffer[sample_offset++] = m->id;
				m->samples++;
			}
			sample_buffer[sample_offset++] = (size_t)info->frame_buffer[frame_count - 1].location;

			auto err = samples.push(reinterpret_cast<void *>(sample_buffer), sample_offset * sizeof(*sample_buffer));
			if (ZSTD_isError(err)) {
				printf("profiler: compress error: %lu %s\n", err, ZSTD_getErrorName(err));
				return PROF_ERR_COMPRESS;
			}
			sample_offset = 0;
		}

		err = jvmtiFree(stack_info);
		if (err != JVMTI_ERROR_NONE) {
			return err;
		}

		write_event_buffer();

		return 0;
	}

	void write_event_buffer() {
		std::lock_guard<std::mutex> guard(this->event_buffer_mutex);
		this->event_buffer.push_back(PROF_EV_NULL);
		samples.push(reinterpret_cast<void *>(&event_buffer[0]), event_buffer.size() * sizeof(event_buffer[0]));
		event_buffer.resize(0);
		auto now = absl::Now();
		push_event_time(now);
		event_buffer_start = now;
	}

	void push_event_time(absl::Time time) {
		event_buffer.push_back((time - event_buffer_start) / absl::Nanoseconds(1));
	}

	void gc_start() {
		last_gc_start = absl::Now();
	}

	void gc_finish() {
		if (last_gc_start != absl::Time()) {
			auto now = absl::Now();

			std::lock_guard<std::mutex> guard(this->event_buffer_mutex);
			event_buffer.push_back(PROF_EV_GC);
			push_event_time(last_gc_start);
			push_event_time(now);
		}
	}

	void run(JNIEnv *env) {
		std::lock_guard<std::mutex> running_guard(this->wait_done);

		absl::Time last = absl::Now();
		this->start_time = last;
		this->event_buffer_start = last;
		this->event_buffer.push_back(0);	// time offset

		bool hasHeapEvents = false;
		jvmtiCapabilities cap = {0};
		cap.can_generate_garbage_collection_events = 1;
		if (jvmti->AddCapabilities(&cap) == JVMTI_ERROR_NONE) {
			hasHeapEvents = true;
		}

		if (hasHeapEvents) {
			jvmtiEventCallbacks callbacks = {0};
			callbacks.GarbageCollectionStart = ::gc_start;
			callbacks.GarbageCollectionFinish = ::gc_finish;
			jvmti->SetEventCallbacks(&callbacks, sizeof(callbacks));
			jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_GARBAGE_COLLECTION_START, nullptr);
			jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_GARBAGE_COLLECTION_FINISH, nullptr);
		}

		for (; this->running.load();) {
			absl::Time now = absl::Now();
			auto err = this->sample(env, now - last);
			last = now;
			if (err != 0) {
				printf("sampler error: %d\n", err);
				break;
			}
			this->num_samples++;

			absl::Time next = this->start_time + (this->num_samples * this->sample_rate);
			auto delay = next - absl::Now();
			if (delay > absl::Seconds(0)) {
				std::this_thread::sleep_for(std::chrono::nanoseconds(delay / absl::Nanoseconds(1)));
			}
		}
		this->stop_time = absl::Now();

		if (hasHeapEvents) {
			jvmtiEventCallbacks callbacks = {0};
			jvmti->SetEventCallbacks(&callbacks, sizeof(callbacks));
			jvmti->RelinquishCapabilities(&cap);
			jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_GARBAGE_COLLECTION_START, nullptr);
			jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_GARBAGE_COLLECTION_FINISH, nullptr);
		}
	}

	int stop(JNIEnv *env, uint8_t *extra, size_t extra_length) {
		running.store(false);
		std::lock_guard<std::mutex> running_guard(this->wait_done);

		auto err = samples.end();
		if (ZSTD_isError(err)) {
			printf("profiler: compress error: %lu %s\n", err, ZSTD_getErrorName(err));
			return PROF_ERR_COMPRESS;
		}

		std::vector<uint64_t> hh;
		hh.push_back(num_samples);
		hh.push_back((this->stop_time - this->start_time) / absl::Microseconds(1));
		hh.push_back(extra_length);
		hh.push_back(thread_count);
		hh.push_back(methods.size());
		header.push(reinterpret_cast<void *>(&hh[0]), hh.size() * sizeof(hh[0]));

		header.push(reinterpret_cast<void *>(extra), extra_length);

		for (auto i = 0; i < thread_count; i++) {
			jvmtiThreadInfo info;
			jvmti->GetThreadInfo(thread_list[i], &info);
			header.push(info.name, strlen(info.name) + 1);
			jvmtiFree(info.name);
		}

		for (auto it = methods.begin(); it != methods.end(); ++it) {
			jclass declaring_klass = nullptr;
			char *name, *signature, *class_name = nullptr;
			jvmti->GetMethodDeclaringClass(it->first, &declaring_klass);
			jvmti->GetClassSignature(declaring_klass, &class_name, nullptr);
			env->DeleteLocalRef(declaring_klass);
			jvmti->GetMethodName(it->first, &name, &signature, nullptr);
			header.push(reinterpret_cast<void *>(&it->second.id), sizeof(it->second.id));
			header.push(class_name, strlen(class_name) + 1);
			header.push(name, strlen(name) + 1);
			header.push(signature, strlen(signature) + 1);
			jvmtiFree(name);
			jvmtiFree(signature);
			jvmtiFree(class_name);
		}

		err = header.end();
		if (ZSTD_isError(err)) {
			printf("profiler: compress error: %lu %s\n", err, ZSTD_getErrorName(err));
			return PROF_ERR_COMPRESS;
		}

		for (auto i = 0; i < thread_count; i++) {
			env->DeleteGlobalRef(thread_list[i]);
		}

		assert(active_profile == this);

		return 0;
	}
};

void JNICALL profile_start(jvmtiEnv *_jvmti, JNIEnv *env, void *profile) {
	reinterpret_cast<Profile *>(profile)->run(env);
}

void JNICALL gc_start(jvmtiEnv *_jvmti) {
	auto p = active_profile;
	if (p != nullptr) {
		p->gc_start();
	}
}

void JNICALL gc_finish(jvmtiEnv *_jvmti) {
	auto p = active_profile;
	if (p != nullptr) {
		p->gc_finish();
	}
}

extern "C" {
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *env, void *reserved) {
	env->GetEnv((void **)&jvmti, JVMTI_VERSION_1_2);
	return JNI_VERSION_1_8;
}

JNIEXPORT jint JNICALL Java_abex_os_debug_Profiler_start0(JNIEnv *env, jclass klass,
	jthread java_thread, jobjectArray threads,
	jint sample_size_bytes, jint sample_rate_us) {
	if (jvmti == nullptr) {
		return PROF_ERR_NOJVMTI;
	}
	if (active_profile != nullptr) {
		return PROF_ERR_ALREADY_RUNNING;
	}

	profiler_klass = (jclass)env->NewGlobalRef(klass);
	get_heap_info = env->GetStaticMethodID(klass, "heapinfo", "()[J");
	if (get_heap_info == nullptr) {
		return PROF_ERR_NO_METHOD;
	}

	auto num_threads = env->GetArrayLength(threads);
	auto thread_list = new jthread[num_threads];
	for (jsize i = 0; i < num_threads; i++) {
		thread_list[i] = env->NewGlobalRef(env->GetObjectArrayElement(threads, i));
	}

	active_profile = new Profile(num_threads, thread_list, sample_size_bytes, sample_rate_us);
	return jvmti->RunAgentThread(java_thread, profile_start, (void *)active_profile, JVMTI_THREAD_NORM_PRIORITY);
}

JNIEXPORT jint JNICALL Java_abex_os_debug_Profiler_stop0(JNIEnv *env, jclass _klass, jbyteArray extra) {
	if (jvmti == nullptr) {
		return PROF_ERR_NOJVMTI;
	}
	if (active_profile == nullptr) {
		return PROF_ERR_NOT_RUNNING;
	}

	jbyte *extra_data = env->GetByteArrayElements(extra, nullptr);
	auto err = active_profile->stop(env, (uint8_t *)extra_data, env->GetArrayLength(extra));
	env->ReleaseByteArrayElements(extra, extra_data, JNI_ABORT);

	return err;
}

JNIEXPORT jbyteArray JNICALL Java_abex_os_debug_Profiler_getBuffer(JNIEnv *env, jclass _klass) {
	if (active_profile == nullptr) {
		return nullptr;
	}

	uint16_t byteMarker = uint16_t{'RP'};
	uint64_t headerSize = active_profile->header.out.pos;
	uint64_t samplesSize = active_profile->samples.out.pos;
	jbyteArray ret = env->NewByteArray(2 + 8 + headerSize + samplesSize);
	int len = 0;
	int offset = 0;
	env->SetByteArrayRegion(ret, offset, len = 2, reinterpret_cast<jbyte *>(&byteMarker));
	offset += len;
	env->SetByteArrayRegion(ret, offset, len = 8, reinterpret_cast<jbyte *>(&headerSize));
	offset += len;
	env->SetByteArrayRegion(ret, offset, len = headerSize, reinterpret_cast<jbyte *>(active_profile->header.out.dst));
	offset += len;
	env->SetByteArrayRegion(ret, offset, len = samplesSize, reinterpret_cast<jbyte *>(active_profile->samples.out.dst));
	offset += len;
	return ret;
}

JNIEXPORT jint JNICALL Java_abex_os_debug_Profiler_free(JNIEnv *env, jclass _klass) {
	if (active_profile == nullptr) {
		return PROF_ERR_NOT_RUNNING;
	}

	assert(active_profile->running.load() == false);

	delete active_profile;
	active_profile = nullptr;

	env->DeleteGlobalRef(profiler_klass);
	profiler_klass = nullptr;

	return 0;
}

JNIEXPORT jint JNICALL Java_abex_os_debug_Profiler_status(JNIEnv *env, jclass _klass) {
	auto prof = active_profile;
	if (prof == nullptr) {
		return 0;
	}
	return prof->running.load() ? 1 : 2;
}

JNIEXPORT jint JNICALL Java_abex_os_debug_Profiler_bufferOffset(JNIEnv *env, jclass _klass) {
	auto prof = active_profile;
	if (prof == nullptr) {
		return 0;
	}
	return prof->samples.out.pos;
}
JNIEXPORT jint JNICALL Java_abex_os_debug_Profiler_bufferSize(JNIEnv *env, jclass _klass) {
	auto prof = active_profile;
	if (prof == nullptr) {
		return 0;
	}
	return prof->samples.out.size;
}
}