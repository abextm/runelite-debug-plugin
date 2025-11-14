/*
 * Copyright (c) 2025 Abex
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

#include "jni.h"

#ifdef _WIN32
#include <memory>
#include <sstream>
#include <iomanip>
#include <windows.h>
#include <psapi.h>
#endif

extern "C" {
JNIEXPORT jstring JNICALL Java_abex_os_debug_MiscNative_dynlibs(JNIEnv *env, jclass _klass) {
#ifdef _WIN32
	auto proc = GetCurrentProcess();

	DWORD needed = 0;
	EnumProcessModules(proc, nullptr, 0, &needed);

	std::unique_ptr<HMODULE[]> modules(new HMODULE[needed / sizeof(HMODULE)]);
	DWORD used = 0;
	EnumProcessModules(proc, modules.get(), needed, &used);

	if (used > needed) {
		used = needed;
	}

	size_t num_modules = used / sizeof(HMODULE);

	std::wstringstream out;
	out << std::hex
		<< std::setfill(L'0')
		<< std::setw(16);

	WCHAR filename[MAX_PATH];
	MODULEINFO moduleInfo;
	for (size_t i = 0; i < num_modules; i++) {
		auto module = modules[i];

		if (GetModuleInformation(proc, module, &moduleInfo, sizeof(moduleInfo))) {
			out << L"0x" << (uintptr_t) moduleInfo.lpBaseOfDll;
			out << L" - 0x" << ((uintptr_t) moduleInfo.lpBaseOfDll + (uintptr_t)moduleInfo.SizeOfImage) << L" \t";
		}

		auto len = GetModuleFileNameW(module, filename, sizeof(filename));
		out.write(filename, len);
		out << L"\n";
	}

	std::wstring str = out.str();
	return env->NewString((const jchar*) str.c_str(), str.length());
#else
	return nullptr;
#endif
}
}