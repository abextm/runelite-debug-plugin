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

import com.sun.management.UnixOperatingSystemMXBean;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import javax.inject.Inject;
import javax.management.ObjectName;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.ui.DynamicGridLayout;

@Slf4j
public class ManagementPanel extends JPanel
{
	@Inject
	public ManagementPanel()
	{
		setLayout(new DynamicGridLayout(0, 1));
		add(new FeedbackButton.CopyToClipboardButton("Dump threads", () -> invokeDiagnosticCommand("threadPrint")));
		add(new FeedbackButton.CopyToClipboardButton("Dump natives", () -> invokeDiagnosticCommand("vmDynlibs")));

		add(new FeedbackButton.CopyToClipboardButton("OS stats", this::dumpOSStats));

		JCheckBox logGC = new JCheckBox("Log GC details");
		add(logGC);
		logGC.addItemListener(ev -> setXLog("gc", "gc=" + (logGC.isSelected() ? "debug" : "off")));
	}

	@SneakyThrows
	private static String invokeDiagnosticCommand(String action, String... args)
	{
		return (String) ManagementFactory.getPlatformMBeanServer().invoke(
			new ObjectName("com.sun.management:type=DiagnosticCommand"),
			action,
			new Object[]{args},
			new String[]{String[].class.getName()}
		);
	}

	private static void setXLog(String name, String what)
	{
		File f = new File(RuneLite.LOGS_DIR, name + ".log");
		String out = invokeDiagnosticCommand("vmLog",
			"output=file=" + f.getAbsolutePath() + "",
			"what=" + what,
			"decorators=uptime"
		);
		log.info("xlog: {}", out);
	}

	private String dumpOSStats()
	{
		OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
		StringBuilder sb = new StringBuilder();
		Class<?> clazz = OperatingSystemMXBean.class;
		for (Class<?> test : new Class[]{
			UnixOperatingSystemMXBean.class,
			com.sun.management.OperatingSystemMXBean.class
		})
		{
			if (test.isAssignableFrom(bean.getClass()))
			{
				clazz = test;
				break;
			}
		}
		for (Method m : clazz.getMethods())
		{
			if (m.getName().startsWith("get") && Modifier.isPublic(m.getModifiers()) && !Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0)
			{
				Object v;
				try
				{
					v = m.invoke(bean);
				}
				catch (Exception e)
				{
					v = e;
				}

				sb.append(m.getName()).append(": ").append(v).append('\n');
			}
		}
		return sb.toString();
	}
}
