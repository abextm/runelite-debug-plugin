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
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Comparator;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.util.OSType;

@Slf4j
@Singleton
public class CoreDumpPanel extends JPanel
{
	private final JLabel statusLabel = new JLabel();
	private final JCheckBox checkBox = new JCheckBox("Core Dumps");
	private final ConfigManager configManager;
	private VMConfigPatch patch;

	@Inject
	public CoreDumpPanel(ConfigManager configManager, Gson gson, @Named("developerMode") boolean developerMode)
	{
		this.configManager = configManager;

		if (developerMode)
		{
			JButton crash = new JButton("crash the client");
			crash.addActionListener(_ev ->
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
		}

		if (OSType.getOSType() != OSType.Windows)
		{
			return;
		}

		this.patch = new VMConfigPatch(gson, "Coredumps", "-XX:+CreateCoredumpOnCrash");
		statusLabel.setText(patch.status());

		setLayout(new DynamicGridLayout(0, 1));

		add(checkBox);
		add(statusLabel);

		checkExistingDumps();
		checkBox.setEnabled(VMConfigPatch.isSupported());
		checkBox.setSelected(configManager.getConfiguration(DebugConfig.GROUP, DebugConfig.CREATE_CORE_DUMP, boolean.class) == Boolean.TRUE);
		checkBox.addChangeListener(e ->
		{
			configManager.setConfiguration(DebugConfig.GROUP, DebugConfig.CREATE_CORE_DUMP, checkBox.isSelected());
		});

		patch();
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
		lastDump.addActionListener(_ev -> DebugPlugin.openExplorer(dumps[0]));
		add(lastDump);
	}

	public void patch()
	{
		if (OSType.getOSType() != OSType.Windows || !VMConfigPatch.isSupported())
		{
			return;
		}

		boolean enabled = configManager.getConfiguration(DebugConfig.GROUP, DebugConfig.CREATE_CORE_DUMP, boolean.class) == Boolean.TRUE;
		patch.set(enabled);
		SwingUtilities.invokeLater(() ->
		{
			checkBox.setSelected(enabled);
			statusLabel.setText(patch.status());
		});
	}

}
