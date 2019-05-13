package main;

import static main.Constants.HEX_FORMAT;

import java.awt.image.BufferedImage;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public enum JpgStatService {

	INSTANCE;

	private JpgStatService() {
	}

	/**
	 * Start the JpgStatService before the first payload is queued
	 */
	public final void start() {
		// start monitoring the queue
		queueHandlingService.execute(new QueueHandlingTask(queue));
		queueHandlerStarted = true;

		setPrintOut();
		setPrintErr();
	}

	/**
	 * Stop the JpgStatService after the last payload has been queued
	 */
	public final void stop() {
		// terminate the queue handling thread once the task returns
		queueHandlingService.shutdown();

		// stop accepting payloads and exit task once queue is cleared
		shutdownQueueHandler = true;

		LOGGER.info("Awaiting queue handler termination");
		boolean queueHandlerTerminated = false;
		while (!queueHandlerTerminated) {
			try {
				queueHandlerTerminated = queueHandlingService.awaitTermination(1, TimeUnit.SECONDS);
				LOGGER.info("queueHandlerTerminated? {}", queueHandlerTerminated);
			} catch (InterruptedException e) {
				LOGGER.error("Error while waiting for queue handler to complete", e);
			}
		}

		// queue handler service is terminated, therefore all payloads have been passed
		// to the payloadProcessingService

		// once all payload tasks are complete, shutdown the service
		payloadProcessingService.shutdown();

		LOGGER.info("Awaiting payload processor termination");
		boolean payloadProcessorTerminated = false;
		while (!payloadProcessorTerminated) {
			try {
				payloadProcessorTerminated = payloadProcessingService.awaitTermination(1, TimeUnit.SECONDS);
				LOGGER.info("payloadProcessorTerminated? {}", payloadProcessorTerminated);
			} catch (InterruptedException e) {
				LOGGER.error("Error while waiting for payload processor to complete", e);
			}
		}
	}

	public final boolean shouldShutdownQueueHandler() {
		return shutdownQueueHandler;
	}

	public final boolean shouldShutdownPayloadProcessor() {
		return queueHandlingService.isTerminated();
	}

	/**
	 * Submit payload for analysis
	 * 
	 * @param payload
	 * @throws InterruptedException
	 * @throws IllegalStateException if service is not running
	 */
	public void submitPayload(Payload payload) throws InterruptedException {
		if (!queueHandlerStarted) {
			throw new IllegalStateException("The service must be started before submitting payloads for processing.");
		}
		// blocks if queue is full
		queue.put(payload);
	}

	/**
	 * Establish logging for successful results
	 */
	private final void setPrintOut() {

		FileWriter fileWriter = null;
		try {
			fileWriter = new FileWriter(Constants.OUTPUT_FILE);
			printOutWriter = new PrintWriter(fileWriter);
		} catch (IOException e) {
			e.printStackTrace();
			LOGGER.error("IOException");
		}
	}

	/**
	 * Establish logging for errors
	 */
	private final void setPrintErr() {

		try {
			FileWriter fileWriter = new FileWriter(Constants.ERROR_FILE);
			printErrWriter = new PrintWriter(fileWriter);
			printErrWriter.println(LocalDateTime.now());
		} catch (IOException e) {
			e.printStackTrace();
			LOGGER.error("IOException");
		}
	}

	/**
	 * Log Result
	 * 
	 * @param logLine String to log to output file
	 */
	public void logResult(String logLine) {
		printOutWriter.println(logLine);
		printOutWriter.flush();
	}

	/**
	 * Log Error
	 * 
	 * @param logLine String to log to output file
	 */
	public void logError(String logLine) {
		printErrWriter.println(logLine);
		printErrWriter.flush();
	}

	/**
	 * Pull Payload elements off of the queue and send them on to be processed
	 */
	private static final class QueueHandlingTask implements Runnable {

		public QueueHandlingTask(BlockingQueue<Payload> queue) {
			this.queue = queue;
		}

		@Override
		public void run() {
			while (!Thread.currentThread().isInterrupted()) {
				// blocks for 1 second if queue is empty, then tries again
				try {
					Payload payload = queue.poll(1L, TimeUnit.SECONDS);
					if (payload != null) {
						INSTANCE.payloadProcessingService.execute(new PayloadProcessingTask(payload));
					} else {
						if (INSTANCE.shouldShutdownQueueHandler() && queue.peek() == null) {
							LOGGER.info("Exiting from the queue handling service");
							return;
						}
					}
				} catch (InterruptedException e) {
					LOGGER.warn("Thread Interrupt Request");
					Thread.currentThread().interrupt();
				} catch (Exception e) {
					LOGGER.error("Error", e);
				}
			}
		}

		private final BlockingQueue<Payload> queue;
	}

	/**
	 * Process the Payload
	 */
	private static final class PayloadProcessingTask implements Runnable {

		public PayloadProcessingTask(Payload payload) {
			this.payload = payload;
		}

		@Override
		public final void run() {

			if (payload == null) {
				return;
			}

			LOGGER.debug("Processing payload for {}", payload);

			try {
				String urlString = payload.getUrlString();
				BufferedImage bufferedImage = payload.getBufferedImage();

				int imageHeight = bufferedImage.getHeight();
				int imageWidth = bufferedImage.getWidth();
				/*
				 * Based on a few samples this sizing seems to cover most cases without having
				 * to grow the Map. Key is color (RGB), Value is count
				 */
				Map<Integer, LongAdder> colorCounter = new HashMap<>(131_072);

				for (int x = 0; x < imageWidth; x++) {
					for (int y = 0; y < imageHeight; y++) {
						int rgb = 0x00FFFFFF & bufferedImage.getRGB(x, y);
						colorCounter.computeIfAbsent(rgb, (unused) -> new LongAdder()).increment();
					}
				}

				LOGGER.debug("Distinct colors for {}: {}", urlString, colorCounter.size());

				// put the Map entries in a List and then sort the list descending
				List<Map.Entry<Integer, LongAdder>> colorFrequency = new LinkedList<>(colorCounter.entrySet());
				Collections.sort(colorFrequency, Comparator
						.<Entry<Integer, LongAdder>>comparingLong((entry) -> (entry).getValue().sum()).reversed());

				// the first element in the List is the most frequent color
				Integer firstColor = null;
				if (colorFrequency.size() > 0) {
					Map.Entry<Integer, LongAdder> colorEntry = colorFrequency.get(0);
					LOGGER.debug("frequency of first: {}", colorEntry.getValue().sum());
					firstColor = colorEntry.getKey();
				}

				Integer secondColor = null;
				if (colorFrequency.size() > 1) {
					Map.Entry<Integer, LongAdder> colorEntry = colorFrequency.get(1);
					LOGGER.debug("frequency of second: {}", colorEntry.getValue().sum());
					secondColor = colorEntry.getKey();
				}

				Integer thirdColor = null;
				if (colorFrequency.size() > 2) {
					Map.Entry<Integer, LongAdder> colorEntry = colorFrequency.get(2);
					LOGGER.debug("frequency of third: {}", colorEntry.getValue().sum());
					thirdColor = colorEntry.getKey();
				}

				String lineOutput = String.join(Constants.DELIMITER, urlString, intAsHex(firstColor),
						intAsHex(secondColor), intAsHex(thirdColor));

				LOGGER.info(lineOutput);
				INSTANCE.logResult(lineOutput);
			} catch (Exception e) {
				String errorMessage = payload.getUrlString() + e.getMessage();
				INSTANCE.logError(errorMessage);
			}
		}

		private final Payload payload;

		private static final Logger LOGGER = LoggerFactory.getLogger(PayloadProcessingTask.class);
	}

	/**
	 * @param i Integer to be display as Hex
	 * @return Hex representation of provided integer as String
	 */
	public static final String intAsHex(Integer i) {
		if (i == null) {
			return "--";
		}
		return String.format(HEX_FORMAT, i.intValue());
	}

	// PrintWriter to log successful results
	private PrintWriter printOutWriter;
	// PrintWriter to log errors
	private PrintWriter printErrWriter;

	private volatile boolean queueHandlerStarted = false;
	private volatile boolean shutdownQueueHandler = false;

	private final BlockingQueue<Payload> queue = new LinkedBlockingQueue<>(QUEUE_SIZE);
	private final ExecutorService queueHandlingService = Executors.newSingleThreadExecutor();
	private final ExecutorService payloadProcessingService = Executors.newFixedThreadPool(PROCESSING_POOL_SIZE);

	private static final int QUEUE_SIZE = 10;
	private static final int PROCESSING_POOL_SIZE = 2;

	private static final Logger LOGGER = LoggerFactory.getLogger(JpgStatService.class);

}
