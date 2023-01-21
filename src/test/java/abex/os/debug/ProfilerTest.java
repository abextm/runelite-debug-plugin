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

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

@Slf4j
public class ProfilerTest
{
	@Before
	public void before()
	{
		Profiler.init();
	}

	private long iteration(long value)
	{
		if ((value & 1) != 0)
		{
			value = value >>> 20 | value << 44;
		}
		else
		{
			value = iteration_inner(value);
		}
		return value;
	}

	private long iteration_inner(long value)
	{
		return value * 31;
	}

	@Test
	public void profile() throws InterruptedException
	{
		Semaphore done = new Semaphore(0);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread test = new Thread(() ->
		{
			long start = System.nanoTime();
			long v = start;
			try
			{
				for (; (System.nanoTime() - start) < 1_000_000_000L; )
				{
					for (long i = 0; i < 0xFFFFL; i++)
					{
						v = iteration(v);
					}
				}
				System.gc();
				for (; (System.nanoTime() - start) < 3_000_000_000L; )
				{
					for (long i = 0; i < 0xFFFFL; i++)
					{
						v = iteration(v);
					}
				}
				Profiler.stop(new byte[0]);
			}
			catch (Throwable t)
			{
				failure.set(t);
				throw t;
			}
			finally
			{
				log.info("got {} in {} ms", v, (System.nanoTime() - start) / 1_000_000);
				done.release();
			}
		}, "profile test thread");
		test.start();
		Profiler.start(new Thread[]{test}, 1024 * 1024, 1000);
		done.acquire();
		if (failure.get() != null)
		{
			Assert.fail();
		}
	}
}
