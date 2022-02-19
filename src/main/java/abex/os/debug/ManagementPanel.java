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

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.lang.management.ManagementFactory;
import javax.management.ObjectName;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.Timer;
import net.runelite.client.ui.DynamicGridLayout;

public class ManagementPanel extends JPanel
{
	private static final String DUMP_THREADS = "Dump threads";

	public ManagementPanel()
	{
		setLayout(new DynamicGridLayout(0, 1));
		add(new ManagementButton("Dump threads", "threadPrint"));
		add(new ManagementButton("Dump natives", "vmDynlibs"));
	}

	static class ManagementButton extends JButton
	{
		private final String text;
		private final String action;
		private final String[] args;

		public ManagementButton(String text, String action, String ...args)
		{
			this.text = text;
			this.action = action;
			this.args = args;
			this.addActionListener(this::click);
			setText(text);
		}

		private void click(ActionEvent ev)
		{
			String s;
			try
			{
				s = (String) ManagementFactory.getPlatformMBeanServer().invoke(
					new ObjectName("com.sun.management:type=DiagnosticCommand"),
					action,
					new Object[]{args},
					new String[]{String[].class.getName()}
				);
			}
			catch (Exception e)
			{
				s = e.toString();
			}

			copyToClipboard(s);
			setText("Copied to clipboard");
			Timer t = new Timer(5 * 1000, v -> setText(text));
			t.setRepeats(false);
			t.start();
		}
	}

	private static void copyToClipboard(String s)
	{
		Toolkit.getDefaultToolkit()
			.getSystemClipboard()
			.setContents(new StringSelection(s), null);
	}
}
