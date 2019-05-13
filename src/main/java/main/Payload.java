package main;

import java.awt.image.BufferedImage;

public class Payload {

	public Payload(String urlString, BufferedImage bufferedImage) {
		this.urlString = urlString;
		this.bufferedImage = bufferedImage;
	}

	public String getUrlString() {
		return urlString;
	}

	public BufferedImage getBufferedImage() {
		return bufferedImage;
	}

	@Override
	public String toString() {
		return urlString;
	}

	private final String urlString;
	private final BufferedImage bufferedImage;
}
