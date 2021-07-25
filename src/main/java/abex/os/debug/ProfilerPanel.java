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

import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.swing.GroupLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.Timer;
import javax.swing.filechooser.FileSystemView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.RuneLiteProperties;
import net.runelite.client.eventbus.EventBus;

@Slf4j
@Singleton
public class ProfilerPanel extends JPanel
{
	private static final String KEY_SETUP = "setup";
	private static final String KEY_RUNNING = "running";
	private static final String KEY_STOPPED = "stopped";
	private static final String KEY_FAILURE = "failure";

	private final Client client;
	private final Gson gson;
	private final EventBus eventBus;

	private final SetupPanel setupPanel = new SetupPanel();
	private final RunningPanel runningPanel = new RunningPanel();
	private final StoppedPanel stoppedPanel = new StoppedPanel();
	private final FailurePanel failurePanel = new FailurePanel();

	private final CardLayout layout = new CardLayout();

	private final Map<String, Object> extra = new HashMap<>();

	private final List<EventEvent<?>> eventEvents = ImmutableList.of(
		new EventEvent<>(0x10001, GameStateChanged.class, e -> new int[]{e.getGameState().getState()}),
		new EventEvent<>(0x10002, GameTick.class)
	);

	private Thread executorThread;
	private byte[] data;

	private class SetupPanel extends JPanel
	{
		private final JButton start = new JButton("Start profiling");
		private final JSpinner sampleDelay = new JSpinner(new SpinnerNumberModel(500, 0, 100_000, 100));
		private final JSpinner sampleBufferSize = new JSpinner(new SpinnerNumberModel(7 * 1024, 1024, 128 * 1024, 1));

		{
			start.addActionListener(ev -> startProfiling());
			sampleDelay.setToolTipText("How many µs per sample");
			sampleBufferSize.setToolTipText("How many KiB to reserve for storing samples");

			JLabel sampleDelayLabel = new JLabel("µs per sample");
			JLabel sampleBufferSizeLabel = new JLabel("KiB buffer");

			GroupLayout l = new GroupLayout(this);
			setLayout(l);
			l.setHorizontalGroup(l.createParallelGroup()
				.addGroup(l.createSequentialGroup()
					.addComponent(sampleDelay)
					.addComponent(sampleDelayLabel))
				.addGroup(l.createSequentialGroup()
					.addComponent(sampleBufferSize)
					.addComponent(sampleBufferSizeLabel))
				.addComponent(start));
			l.setVerticalGroup(l.createSequentialGroup()
				.addGroup(l.createParallelGroup(GroupLayout.Alignment.BASELINE)
					.addComponent(sampleDelay)
					.addComponent(sampleDelayLabel))
				.addGroup(l.createParallelGroup(GroupLayout.Alignment.BASELINE)
					.addComponent(sampleBufferSize)
					.addComponent(sampleBufferSizeLabel))
				.addComponent(start));
		}
	}

	private class RunningPanel extends JPanel
	{
		private final JButton stop = new JButton("Stop profiling");
		private final JProgressBar buffer = new JProgressBar();

		{
			stop.addActionListener(ev -> stopProfiling());

			GroupLayout l = new GroupLayout(this);
			setLayout(l);
			l.setHorizontalGroup(l.createParallelGroup()
				.addComponent(stop)
				.addComponent(buffer));
			l.setVerticalGroup(l.createSequentialGroup()
				.addComponent(stop)
				.addComponent(buffer));
		}
	}

	private class StoppedPanel extends JPanel
	{
		private final JButton clear = new JButton("Delete profile");
		private final JButton save = new JButton("Save Profile");
		private final JLabel status = new JLabel("");

		{
			clear.addActionListener(ev ->
			{
				data = null;
				ProfilerPanel.this.show(KEY_SETUP);
			});
			save.addActionListener(ev ->
			{
				JFileChooser fc = new JFileChooser();
				fc.setDialogTitle("Save profile");
				fc.setSelectedFile(new File(FileSystemView.getFileSystemView().getDefaultDirectory(), "profile.rlp"));
				if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION)
				{
					try
					{
						Files.write(fc.getSelectedFile().toPath(), data);
						status.setText("Saved");
					}
					catch (IOException e)
					{
						log.warn("failed to save", e);
						status.setText(e.getMessage());
						status.revalidate();
					}
				}
			});

			GroupLayout l = new GroupLayout(this);
			setLayout(l);
			l.setHorizontalGroup(l.createParallelGroup()
				.addGroup(l.createSequentialGroup()
					.addComponent(clear)
					.addComponent(save))
				.addComponent(status));
			l.setVerticalGroup(l.createSequentialGroup()
				.addGroup(l.createParallelGroup()
					.addComponent(clear)
					.addComponent(save))
				.addComponent(status));
		}
	}

	private class FailurePanel extends JPanel
	{
		private final JLabel label = new JLabel("Profiler not supported");

		{
			setLayout(new BorderLayout());
			add(label, BorderLayout.CENTER);
		}
	}

	@RequiredArgsConstructor
	private static class EventEvent<T>
	{
		private final int id;
		private final Class<T> clazz;
		private final Function<T, int[]> consumer;
		private EventBus.Subscriber subscriber = null;

		EventEvent(int id, Class<T> clazz)
		{
			this(id, clazz, null);
		}

		void register(EventBus eventBus)
		{
			assert this.subscriber == null;
			this.subscriber = eventBus.register(clazz, ev ->
			{
				int[] data = consumer == null ? null : consumer.apply(ev);
				Profiler.pushEvent(id, data);
			}, 0);
		}

		void unregister(EventBus eventBus)
		{
			eventBus.unregister(this.subscriber);
			this.subscriber = null;
		}
	}

	@Inject
	public ProfilerPanel(
		Client client, ScheduledExecutorService executor, Gson gson, EventBus eventBus,
		@Named("runelite.version") String runeliteVersion)
	{
		this.gson = gson;
		this.client = client;
		this.eventBus = eventBus;

		executor.submit(() ->
		{
			executorThread = Thread.currentThread();
		});

		setLayout(layout);
		add(setupPanel, KEY_SETUP);
		add(runningPanel, KEY_RUNNING);
		add(stoppedPanel, KEY_STOPPED);
		add(failurePanel, KEY_FAILURE);
		show(KEY_SETUP);

		extra.put("buildID", client.getBuildID());
		extra.put("version", runeliteVersion);
		extra.put("launcherVersion", RuneLiteProperties.getLauncherVersion());
		for (String prop : new String[]{
			"os.name", "os.version", "os.arch", "java.vendor", "java.version",
		})
		{
			extra.put(prop, System.getProperty(prop));
		}
	}

	private void show(String key)
	{
		layout.show(this, key);
		revalidate();
	}

	public void startProfiling()
	{
		log.info("Starting profiling");
		Thread[] threads = Stream.of(
			client.getClientThread(),
			Thread.currentThread(),
			executorThread
		).filter(Objects::nonNull)
			.toArray(Thread[]::new);

		int delay = (Integer) setupPanel.sampleDelay.getValue();
		extra.put("delay", delay);
		try
		{
			Profiler.start(
				threads,
				(Integer) setupPanel.sampleBufferSize.getValue() * 1024,
				delay);
		}
		catch (Exception | LinkageError e)
		{
			show(KEY_FAILURE);
			log.info("error starting profiler", e);
			return;
		}

		for (EventEvent<?> ev : eventEvents)
		{
			ev.register(eventBus);
		}

		show(KEY_RUNNING);
		final Timer timer = new Timer(100, null);
		timer.addActionListener(evAction ->
		{
			Profiler.Status status = Profiler.status();
			switch (status)
			{
				case RUNNING:
					runningPanel.buffer.setValue(Profiler.bufferOffset());
					runningPanel.buffer.setMaximum(Profiler.bufferSize());
					break;
				case FAILED:
					stopProfiling();
					// fallthrough
				case STOPPED:
				case NOT_RUNNING:
					show(KEY_STOPPED);
					timer.stop();

					for (EventEvent<?> ev : eventEvents)
					{
						ev.unregister(eventBus);
					}
					break;
			}
		});
		timer.start();
	}

	public void stopProfiling()
	{
		String extraString = gson.toJson(extra);
		data = Profiler.stop(extraString.getBytes(StandardCharsets.UTF_8));
		show(KEY_STOPPED);
		stoppedPanel.status.setText("");
	}
}
