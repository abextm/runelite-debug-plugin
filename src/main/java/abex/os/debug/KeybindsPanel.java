/*
 * Copyright (c) 2022 Abex
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

import javax.inject.Inject;
import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigDescriptor;
import net.runelite.client.config.ConfigItemDescriptor;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.ModifierlessKeybind;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.DynamicGridLayout;

public class KeybindsPanel extends JPanel
{
	private final ConfigManager configManager;
	private final PluginManager pluginManager;

	@Inject
	public KeybindsPanel(ConfigManager configManager, PluginManager pluginManager)
	{
		this.configManager = configManager;
		this.pluginManager = pluginManager;

		setLayout(new DynamicGridLayout(0, 1));

		JButton displayKeybinds = new JButton("Display Keybinds");
		add(displayKeybinds);
		displayKeybinds.addActionListener(ev -> showKeybindsPanel());
	}

	private void showKeybindsPanel()
	{
		JTextArea textArea = new JTextArea(showKeybinds(), 30, 50);
		JOptionPane.showMessageDialog(null, new JScrollPane(textArea), "Keybinds", JOptionPane.INFORMATION_MESSAGE);
	}

	private String showKeybinds()
	{
		String out = "";
		for (Plugin plugin : pluginManager.getPlugins())
		{
			if (!pluginManager.isPluginEnabled(plugin))
			{
				continue;
			}

			Config config = pluginManager.getPluginConfigProxy(plugin);
			ConfigDescriptor cd = config == null ? null : configManager.getConfigDescriptor(config);
			if (cd == null)
			{
				continue;
			}

			for (ConfigItemDescriptor cid : cd.getItems())
			{
				if (cid.getType() == Keybind.class || cid.getType() == ModifierlessKeybind.class)
				{
					Keybind keybind = configManager.getConfiguration(cd.getGroup().value(), cid.getItem().keyName(), cid.getType());
					if (Keybind.NOT_SET.equals(keybind))
					{
						continue;
					}

					out += plugin.getName() + " " + cid.name() + ": " + keybind + "\n";
				}
			}
		}

		return out;
	}
}
