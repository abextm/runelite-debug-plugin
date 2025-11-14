package abex.os.debug;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MiscNative
{
	public static boolean loaded()
	{
		if (Profiler.getInitialized() == null)
		{
			try
			{
				Profiler.init();
			}
			catch (Throwable e)
			{
				log.info("Failed to load libprofiler", e);
			}
		}

		return Profiler.getInitialized();
	}

	public static native String dynlibs();
}
