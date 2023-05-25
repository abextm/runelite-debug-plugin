package abex.os.debug;

import com.google.common.base.Strings;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JPanel;
import javax.swing.filechooser.FileSystemView;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.util.OSType;

@Slf4j
public class VirtualMemoryPanel extends JPanel
{
	VirtualMemoryPanel()
	{
		if (OSType.getOSType() != OSType.Windows)
		{
			return;
		}

		setLayout(new DynamicGridLayout(0, 1));

		JButton vmem = new JButton("Virtual Memory");
		vmem.addActionListener(_ev ->
		{
			JFileChooser fc = new JFileChooser();
			fc.setDialogTitle("Save page table");
			fc.setSelectedFile(new File(FileSystemView.getFileSystemView().getDefaultDirectory(), "vmem.txt"));
			if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION)
			{
				return;
			}

			recordVirtualMemory(fc.getSelectedFile());
		});

		JCheckBox onOOM = new JCheckBox("Record VMem on OOM");
		onOOM.addItemListener(ev ->
		{
			if (onOOM.isSelected())
			{
				onOOM.setEnabled(false);

				Thread.UncaughtExceptionHandler rl = Thread.getDefaultUncaughtExceptionHandler();
				Thread.setDefaultUncaughtExceptionHandler((thread, throwable) ->
				{
					if (throwable instanceof OutOfMemoryError)
					{
						recordVirtualMemory(new File(RuneLite.LOGS_DIR, "vmem.txt"));
					}
					rl.uncaughtException(thread, throwable);
				});
			}
		});
		add(onOOM);

		add(vmem);
	}

	private void recordVirtualMemory(File file)
	{
		K32 k32 = Native.load("kernel32", K32.class, W32APIOptions.DEFAULT_OPTIONS);

		try (Writer writer = new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8)))
		{
			WinNT.MEMORY_BASIC_INFORMATION info = new WinNT.MEMORY_BASIC_INFORMATION();
			Pointer offset = Pointer.createConstant(0);
			for (; ; )
			{
				long size = k32.VirtualQuery(offset, info, new BaseTSD.SIZE_T(info.size())).longValue();
				if (size <= 0)
				{
					writer.append("err=").append("" + Native.getLastError()).append('\n');
					break;
				}

				long skip = Pointer.nativeValue(info.baseAddress) - Pointer.nativeValue(offset);
				if (skip > 0)
				{
					writer.append(hex(skip)).append(" free\n");
				}

				long end = Pointer.nativeValue(info.baseAddress) + info.regionSize.longValue();
				writer
					.append(hex(Pointer.nativeValue(info.baseAddress)))
					.append('-')
					.append(hex(end))
					.append(' ')
					.append(hex(info.regionSize.longValue()))
					.append(' ')
					.append(Pointer.nativeValue(info.allocationBase) == Pointer.nativeValue(info.baseAddress) ? 's' : ' ')
					.append(" | ")
					.append((info.protect.intValue() & WinNT.PAGE_EXECUTE) != 0 ? "x " : "  ")
					.append((info.protect.intValue() & WinNT.PAGE_EXECUTE_READ) != 0 ? "rx " : "   ")
					.append((info.protect.intValue() & WinNT.PAGE_EXECUTE_READWRITE) != 0 ? "rwx " : "    ")
					.append((info.protect.intValue() & 0x80 /*PAGE_EXECUTE_WRITECOPY*/) != 0 ? "rcx " : "    ")
					.append((info.protect.intValue() & WinNT.PAGE_NOACCESS) != 0 ? "F " : "  ")
					.append((info.protect.intValue() & WinNT.PAGE_READONLY) != 0 ? "r " : "  ")
					.append((info.protect.intValue() & WinNT.PAGE_READWRITE) != 0 ? "rw " : "   ")
					.append((info.protect.intValue() & WinNT.PAGE_WRITECOPY) != 0 ? "rc " : "   ")
					.append((info.protect.intValue() & WinNT.PAGE_GUARD) != 0 ? 'g' : ' ')
					.append((info.protect.intValue() & 0x200 /*PAGE_NOCACHE*/) != 0 ? 'P' : ' ')
					.append(" | ")
					.append((info.state.intValue() & WinNT.MEM_COMMIT) != 0 ? 'c' : ' ')
					.append((info.state.intValue() & WinNT.MEM_FREE) != 0 ? 'f' : ' ')
					.append((info.state.intValue() & WinNT.MEM_RESERVE) != 0 ? 'r' : ' ')
					.append(" | ")
					.append((info.type.intValue() & WinNT.MEM_IMAGE) != 0 ? 'i' : ' ')
					.append((info.type.intValue() & WinNT.MEM_MAPPED) != 0 ? 'm' : ' ')
					.append((info.type.intValue() & WinNT.MEM_PRIVATE) != 0 ? 'p' : ' ')
					.append(" protect=")
					.append(Integer.toHexString(info.protect.intValue()))
					.append(" allocProtect=")
					.append(Integer.toHexString(info.allocationProtect.intValue()))
					.append('\n');

				if (end < Pointer.nativeValue(offset))
				{
					break;
				}

				Pointer.nativeValue(offset, end);
			}
		}
		catch (IOException e)
		{
			log.warn("unable to write page table", e);
		}
	}

	private static String hex(long value)
	{
		return "0x" + Strings.padStart(Long.toHexString(value), Native.POINTER_SIZE * 2, '0');
	}

	public interface K32 extends StdCallLibrary
	{
		BaseTSD.SIZE_T VirtualQuery(Pointer lpAddress, WinNT.MEMORY_BASIC_INFORMATION lpBuffer, BaseTSD.SIZE_T dwLength);
	}
}
