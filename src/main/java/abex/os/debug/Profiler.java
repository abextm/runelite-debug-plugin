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
package abex.os.debug;

import com.google.common.io.ByteStreams;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.OSType;

@Slf4j
public class Profiler
{
	private static final long[] heapinfo = new long[4];
	private static final MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();

	private static boolean initialized = false;

	public static synchronized void init()
	{
		if (!initialized)
		{
			initialized = true;
			String os = OSType.getOSType().name().toLowerCase(Locale.US);
			String name = "invalid";
			String suffix = ".invalid";
			String arch = System.getProperty("os.arch");
			switch (OSType.getOSType())
			{
				case Windows:
					name = "profiler";
					suffix = ".dll";
					break;
				case Linux:
					name = "libProfiler";
					suffix = ".so";
					break;
				case MacOS:
					name = "libProfiler";
					suffix = ".dylib";
					arch = "universal";
					break;
			}
			String path = "/natives/" + os + "-" + arch + "/" + name + suffix;
			log.info("loading libProfiler from " + path);

			try (InputStream is = Profiler.class.getResourceAsStream(path))
			{
				if (is == null)
				{
					if (Profiler.class.getResource("/natives/cibuilt") == null && System.getenv("RUNELITE_DEBUG_NATIVE_PATH") != null)
					{
						Runtime.getRuntime().load(System.getenv("RUNELITE_DEBUG_NATIVE_PATH"));
						return;
					}

					throw new RuntimeException("no natives: " + path);
				}

				File tmp = File.createTempFile("libprofiler", suffix);
				tmp.deleteOnExit();

				try (FileOutputStream fos = new FileOutputStream(tmp))
				{
					ByteStreams.copy(is, fos);
				}

				Runtime.getRuntime().load(tmp.getAbsolutePath());
			}
			catch (IOException e)
			{
				throw new RuntimeException(e);
			}

			log.info("libProfiler loaded");
		}
	}

	@SuppressWarnings("InstantiatingAThreadWithDefaultRunMethod")
	public static void start(Thread[] threads, int bufferSize, int sampleDelay)
	{
		init();
		int err = start0(new Thread("Profiler Agent"), threads, bufferSize, sampleDelay); // can fit in discord
		if (err != 0)
		{
			throw new RuntimeException("Profiler error " + err);
		}
	}

	private static native int start0(Thread agentThread, Thread[] threads, int sampleBufferSize, int sampleDelay);

	public static byte[] stop(byte[] extra)
	{
		int err = stop0(extra);
		if (err != 0)
		{
			free();
			throw new RuntimeException("Profiler error " + err);
		}

		byte[] buf = getBuffer();
		free();

		return buf;
	}

	private static long[] heapinfo()
	{
		MemoryUsage heap = memBean.getHeapMemoryUsage();
		heapinfo[0] = heap.getUsed();
		heapinfo[1] = heap.getCommitted();

		MemoryUsage offheap = memBean.getNonHeapMemoryUsage();
		heapinfo[2] = offheap.getUsed();
		heapinfo[3] = offheap.getCommitted();

		return heapinfo;
	}

	private static native int pushEvent0(int id, int[] data);

	public static void pushEvent(int id, int[] data)
	{
		int err = pushEvent0(id, data);
		if (err != 0)
		{
			throw new RuntimeException("Profiler error " + err);
		}
	}

	private static native int stop0(byte[] extra);

	private static native int free();

	private static native byte[] getBuffer();

	public static native int status();

	public static native int bufferOffset();

	public static native int bufferSize();
}
