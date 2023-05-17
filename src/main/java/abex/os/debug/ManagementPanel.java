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
import java.awt.Window;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import javax.inject.Inject;
import javax.management.ObjectName;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileSystemView;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.ui.DynamicGridLayout;

@Slf4j
public class ManagementPanel extends JPanel
{
	private final Client client;

	@Inject
	public ManagementPanel(Client client)
	{
		this.client = client;

		setLayout(new DynamicGridLayout(0, 1));
		add(new FeedbackButton.CopyToClipboardButton("Dump threads", () -> invokeDiagnosticCommand("threadPrint")));
		add(new FeedbackButton.CopyToClipboardButton("Dump natives", () -> invokeDiagnosticCommand("vmDynlibs")));

		JButton heapDump = new JButton("Heap dump");
		add(heapDump);
		heapDump.addActionListener(ev -> dumpHeap());

		add(new FeedbackButton.CopyToClipboardButton("OS stats", this::dumpOSStats));
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

	private void dumpHeap()
	{
		JFileChooser fc = new JFileChooser();
		fc.setDialogTitle("Save heap dump");
		fc.setSelectedFile(new File(FileSystemView.getFileSystemView().getDefaultDirectory(), "dump.hprof"));
		if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION)
		{
			return;
		}

		File fi = fc.getSelectedFile();
		fi.delete();
		String filename = fi.getAbsoluteFile().getPath();

		client.setPassword("");

		JFrame frame = new JFrame("Heap dump");
		frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		JLabel l = new JLabel("Taking heap dump<br>This may take a while...");
		l.setBorder(new EmptyBorder(15, 15, 15, 15));
		frame.add(l);
		frame.setType(Window.Type.POPUP);
		frame.pack();
		frame.setVisible(true);
		frame.toFront();

		Timer t = new Timer(300, v ->
		{
			try
			{
				ManagementFactory.getPlatformMBeanServer().invoke(
					new ObjectName("com.sun.management:type=HotSpotDiagnostic"),
					"dumpHeap",
					new Object[]{filename, true},
					new String[]{String.class.getName(), boolean.class.getName()}
				);
			}
			catch (Exception e)
			{
				log.warn("unable to capture heap dump", e);
				JOptionPane.showMessageDialog(this, e.toString(), "Heap dump error", JOptionPane.ERROR_MESSAGE);
			}

			frame.setVisible(false);
		});
		t.setRepeats(false);
		t.start();
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
