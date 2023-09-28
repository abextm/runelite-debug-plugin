#include "jni.h"
#include "zstd.h"
#include "zstd_errors.h"
#include <cstdint>
#include <cstddef>

extern "C" {
JNIEXPORT jint JNICALL Java_abex_os_debug_ZstdOutputStream_cStreamInSize(JNIEnv *_env, jclass _klass) {
	return (jint) ZSTD_CStreamInSize();
}

JNIEXPORT jint JNICALL Java_abex_os_debug_ZstdOutputStream_cStreamOutSize(JNIEnv *_env, jclass _klass) {
	return (jint) ZSTD_CStreamOutSize();
}

JNIEXPORT jlong JNICALL Java_abex_os_debug_ZstdOutputStream_new0(JNIEnv *_env, jclass _klass, jint level) {
	ZSTD_CStream *zcs = ZSTD_createCStream();
	if (zcs) {
		ZSTD_initCStream(zcs, level);
	}
	return (jlong) zcs;
}

JNIEXPORT void JNICALL Java_abex_os_debug_ZstdOutputStream_free0(JNIEnv *_env, jclass _klass, jlong stream) {
	if (!stream) {
		return;
	}

	ZSTD_CStream *zcs = (ZSTD_CStream*) stream;
	ZSTD_freeCStream(zcs);
}

JNIEXPORT jlong JNICALL Java_abex_os_debug_ZstdOutputStream_compress0(JNIEnv *env, jclass _klass, jlong stream, jbyteArray in, jint inOff, jint inLen, jbyteArray out, jint op) {
	if (!stream) {
		return 0;
	}

	ZSTD_CStream *zcs = (ZSTD_CStream*) stream;

	ZSTD_outBuffer outBuffer = { nullptr, 0, 0 };
	outBuffer.size = (size_t) env->GetArrayLength(out);
	outBuffer.dst =	env->GetPrimitiveArrayCritical(out, nullptr);

	ZSTD_inBuffer inBuffer = { nullptr, (size_t) (inOff + inLen), (size_t) inOff };
	inBuffer.src = env->GetPrimitiveArrayCritical(in, nullptr);

	size_t result = 0;
	if (outBuffer.dst && inBuffer.src) {
		result = ZSTD_compressStream2(zcs, &outBuffer, &inBuffer, (ZSTD_EndDirective) op);
	}

	if (inBuffer.src) {
		env->ReleasePrimitiveArrayCritical(in, (void*) inBuffer.src, JNI_ABORT);
	}
	if (outBuffer.dst) {
		env->ReleasePrimitiveArrayCritical(out, outBuffer.dst, 0);
	}

	if (ZSTD_isError(result)) {
		const char *errName = ZSTD_getErrorName(result);
		jclass ioe = env->FindClass("java/io/IOException");
		env->ThrowNew(ioe, errName);
		return 0;
	}

	bool more = result > 0;

	jlong packedOut = (((jlong) (inBuffer.pos - inOff)) << 32)
		| ((more & 1) << 31)
		| (outBuffer.pos & 0x7FFF'FFFF);

	return packedOut;
}
}