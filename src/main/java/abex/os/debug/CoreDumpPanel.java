/*
 * Copyright (c) 2023 Abex
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

import com.google.common.io.PatternFilenameFilter;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonIOException;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLiteProperties;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.util.OSType;

@Slf4j
@Singleton
public class CoreDumpPanel extends JPanel
{
	private static final String COREDUMP_ARG = "-XX:+CreateCoredumpOnCrash";

	private final boolean enabled;
	private final JLabel statusLabel = new JLabel();
	private final JCheckBox checkBox = new JCheckBox("Core Dumps");
	private final Gson gson;
	private boolean coredumpsCurrentlyEnabled;
	private ConfigManager configManager;
	private File packrConfigFile = new File("config.json").getAbsoluteFile();

	@Inject
	public CoreDumpPanel(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.gson = gson.newBuilder()
			.setPrettyPrinting()
			.create();

		/*{
			JButton crash = new JButton("crash the client");
			crash.addActionListener(e_v ->
			{
				try
				{
					Class<?> unsafe = Class.forName("sun.misc.Unsafe");
					Field f = unsafe.getDeclaredField("theUnsafe");
					f.setAccessible(true);
					Object theUnsafe = f.get(null);
					unsafe.getMethod("getInt", long.class).invoke(theUnsafe, 0L);
				}
				catch (ReflectiveOperationException e)
				{
					log.warn("", e);
				}
			});
			add(crash);
		}// */

		if (OSType.getOSType() != OSType.Windows)
		{
			this.enabled = false;
			return;
		}

		coredumpsCurrentlyEnabled = ManagementFactory.getRuntimeMXBean()
			.getInputArguments()
			.stream()
			.anyMatch(COREDUMP_ARG::equals);

		setLayout(new DynamicGridLayout(0, 1));

		add(checkBox);
		add(statusLabel);

		this.enabled = checkEnabled();
		if (coredumpsCurrentlyEnabled)
		{
			statusLabel.setText("Coredumps enabled");
		}

		checkExistingDumps();
		checkBox.setEnabled(enabled);
		checkBox.setSelected(configManager.getConfiguration(DebugConfig.GROUP, DebugConfig.CREATE_CORE_DUMP, boolean.class) == Boolean.TRUE);
		checkBox.addChangeListener(e ->
		{
			configManager.setConfiguration(DebugConfig.GROUP, DebugConfig.CREATE_CORE_DUMP, checkBox.isSelected());
		});
	}

	private boolean checkEnabled()
	{
		String launcherVer = RuneLiteProperties.getLauncherVersion();
		if (launcherVer == null)
		{
			statusLabel.setText("Not using launcher");
			return false;
		}
		if (!launcherVer.startsWith("2"))
		{
			statusLabel.setText("Launcher out of date");
			return false;
		}

		if (!isExeLauncher())
		{
			statusLabel.setText("Not using EXE launcher");
			return false;
		}

		if (!packrConfigFile.exists())
		{
			statusLabel.setText("Cannot find config");
			return false;
		}

		statusLabel.setText("Coredumps disabled");
		return true;
	}

	private void checkExistingDumps()
	{
		File[] dumps = new File(".").getAbsoluteFile().listFiles(new PatternFilenameFilter("hs_err_pid.*\\.mdmp$"));
		if (dumps == null || dumps.length <= 0)
		{
			return;
		}
		if (dumps.length > 1)
		{
			Arrays.sort(dumps, Comparator.comparing(File::lastModified).reversed());
			for (int i = 1; i < dumps.length; i++)
			{
				log.info("deleting {}", dumps[i]);
				dumps[i].delete();
			}
		}

		JButton lastDump = new JButton("Open last coredump");
		lastDump.addActionListener(_ev ->
		{
			try
			{
				new ProcessBuilder("explorer", "/select,\"" + dumps[0].getAbsolutePath() + "\"")
					.start();
			}
			catch (IOException e)
			{
				log.warn("", e);
			}
		});
		add(lastDump);
	}

	public void patch()
	{
		if (!enabled)
		{
			return;
		}

		boolean enabled = configManager.getConfiguration(DebugConfig.GROUP, DebugConfig.CREATE_CORE_DUMP, boolean.class) == Boolean.TRUE;

		JsonObject config;
		try (FileInputStream fin = new FileInputStream(packrConfigFile))
		{
			config = gson.fromJson(new InputStreamReader(fin), JsonObject.class);
		}
		catch (IOException | JsonIOException | JsonSyntaxException e)
		{
			log.warn("error deserializing packr vm args!", e);
			return;
		}

		JsonArray vmArgs = config.get("vmArgs").getAsJsonArray();
		JsonPrimitive coredumpArg = new JsonPrimitive(COREDUMP_ARG);
		if (enabled)
		{
			if (!vmArgs.contains(coredumpArg))
			{
				vmArgs.add(coredumpArg);
			}
		}
		else
		{
			vmArgs.remove(new JsonPrimitive(COREDUMP_ARG));
		}

		try
		{
			File tmpFile = File.createTempFile("runelite", null);

			try (FileOutputStream fout = new FileOutputStream(tmpFile);
				FileChannel channel = fout.getChannel();
				PrintWriter writer = new PrintWriter(fout))
			{
				channel.lock();
				writer.write(gson.toJson(config));
				channel.force(true);
				// FileChannel.close() frees the lock
			}

			try
			{
				Files.move(tmpFile.toPath(), packrConfigFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (AtomicMoveNotSupportedException ex)
			{
				log.debug("atomic move not supported", ex);
				Files.move(tmpFile.toPath(), packrConfigFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
			}

			log.info("patched packr vm args");

			SwingUtilities.invokeLater(() ->
			{
				String status = "Coredumps " + (coredumpsCurrentlyEnabled ? "enabled" : "disabled");
				if (coredumpsCurrentlyEnabled != enabled)
				{
					status += " (Restart to " + (enabled ? "enable" : "disable") + ")";
				}
				statusLabel.setText(status);

			});
		}
		catch (IOException e)
		{
			log.warn("error updating packr vm args!", e);
		}
	}

	private boolean isExeLauncher()
	{
		try
		{
			Class<?> ph = Class.forName("java.lang.ProcessHandle");
			Object currentProcessHandle = ph.getMethod("current").invoke(null);
			Object phInfo = ph.getMethod("info").invoke(currentProcessHandle);
			Class<?> ph$info = Class.forName("java.lang.ProcessHandle$Info");
			Optional<String> command = (Optional<String>) ph$info.getMethod("command").invoke(phInfo);
			return command.get().toLowerCase(Locale.ROOT).endsWith("runelite.exe");
		}
		catch (ReflectiveOperationException ignored)
		{
			return false;
		}
	}
}
