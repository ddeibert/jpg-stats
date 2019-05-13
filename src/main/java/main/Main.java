package main;

import java.awt.image.BufferedImage;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Scanner;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

	public static void main(String[] args) {

		long startMillis = System.currentTimeMillis();
		long payloadCount = 0;

		JpgStatService.INSTANCE.start();

		InputStream urlIs = null;
		try {
			urlIs = new URL(new URL(Constants.PROTOCOL), Constants.SOURCE_FILE).openStream();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			LOGGER.error("FileNotFoundException");
		} catch (MalformedURLException e) {
			e.printStackTrace();
			LOGGER.error("MalformedURLException");
		} catch (IOException e) {
			e.printStackTrace();
			LOGGER.error("IOException");
		}

		Scanner scanner = new Scanner(urlIs, "UTF-8");

		while (scanner.hasNextLine()) {
			String urlString = scanner.nextLine();
			// skip blank lines
			if (urlString.isEmpty()) {
				LOGGER.debug("Skipping blank line");
				continue;
			}

			InputStream imageIs = null;
			try {
				imageIs = new URL(urlString).openStream();
			} catch (MalformedURLException e) {
				e.printStackTrace();
				LOGGER.error("MalformedURLException");
			} catch (IOException e) {
				e.printStackTrace();
				LOGGER.error("IOException");
			}

			if (imageIs == null) {
				String errorMessage = urlString + " - Unable to open URL";
				JpgStatService.INSTANCE.logError(errorMessage);
				// skip to next image
				continue;
			}

			BufferedImage bufferedImage = null;
			try {
				bufferedImage = ImageIO.read(imageIs);
			} catch (IOException e) {
				e.printStackTrace();
				LOGGER.error("IOException");
			}
			
			if (bufferedImage == null) {
				String errorMessage = urlString + " - Unable to obtain image";
				JpgStatService.INSTANCE.logError(errorMessage);
				// skip to next image
				continue;
			}

			try {
				JpgStatService.INSTANCE.submitPayload(new Payload(urlString, bufferedImage));
				payloadCount++;
			} catch (InterruptedException e) {
				e.printStackTrace();
				LOGGER.error("InterruptedException");
			}
		}

		scanner.close();

		try {
			urlIs.close();
		} catch (IOException e) {
			e.printStackTrace();
			LOGGER.error("IOException");
		}

		JpgStatService.INSTANCE.stop();

		long elapsedMillis = System.currentTimeMillis() - startMillis;

		LOGGER.info("***That's all folks!***");
		LOGGER.info("Processed {} urls in {} seconds.", payloadCount, elapsedMillis / 1_000);
		LOGGER.info("See {} and {} for additional details.", Constants.OUTPUT_FILE, Constants.ERROR_FILE);
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);
}
