package abex.os.debug;

import java.awt.Dimension;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;

public class RetainedSizeAnalyzer
{
	public static void main(String... args) throws Exception
	{
		File hprof = args.length > 0 ? new File(args[0]) : null;
		try
		{
			if (hprof == null)
			{
				throw new IllegalArgumentException("missing heap dump path");
			}
			showRetainedSizes(hprof);
		}
		finally
		{
			if (hprof != null)
			{
				hprof.delete();
			}
		}
	}

	private static void showRetainedSizes(File hprof) throws IOException
	{
		HProfStripper.RetainedSizeResult rsr;
		try (var stripper = new HProfStripper(hprof))
		{
			rsr = stripper.runRetainedSizeComputer();
		}

		Map<String, Long> pluginRetainedSizes = new HashMap<>();
		Map<String, Integer> pluginNumObjects = new HashMap<>();
		for (int n = 0; n < rsr.num; ++n)
		{
			String parentClazz = rsr.parentClazz(n);
			if (parentClazz != null && parentClazz.equals("net/runelite/client/plugins/Plugin"))
			{
				String clazz = rsr.clazz(n);
				pluginRetainedSizes.put(clazz, rsr.retainedSize[n]);
				pluginNumObjects.put(clazz, rsr.numObjects[n]);
			}
		}

		Map<String, Long> sorted =
			pluginRetainedSizes.entrySet().stream()
				.sorted(Map.Entry.<String, Long>comparingByValue().reversed())
				.collect(Collectors.toMap(
					Map.Entry::getKey,
					Map.Entry::getValue,
					(a, b) -> a,
					LinkedHashMap::new
				));

		DefaultTableModel model = new DefaultTableModel(
			new Object[]{"Plugin", "Size (KB)", "Num objects"}, 0
		)
		{
			@Override
			public boolean isCellEditable(int row, int column)
			{
				return false;
			}
		};

		for (var entry : sorted.entrySet())
		{
			model.addRow(new Object[]{
				entry.getKey(),
				String.format("%,d", entry.getValue() / 1024),
				String.format("%,d", pluginNumObjects.get(entry.getKey()))
			});
		}

		JTable table = new JTable(model);
		table.setFillsViewportHeight(true);
		table.setAutoCreateRowSorter(false);

		table.getColumnModel().getColumn(0).setPreferredWidth(400);
		table.getColumnModel().getColumn(1).setPreferredWidth(150);

		JScrollPane scrollPane = new JScrollPane(
			table,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
			JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
		);

		scrollPane.setPreferredSize(new Dimension(800, 600));

		showFrame(scrollPane);
	}

	private static void showFrame(JScrollPane scrollPane)
	{
		JFrame frame = new JFrame("Memory Analyzer");
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.add(scrollPane);
		frame.pack();
		frame.setLocationByPlatform(true);
		frame.setVisible(true);
		frame.toFront();
	}
}
