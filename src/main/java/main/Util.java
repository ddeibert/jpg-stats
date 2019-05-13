package main;

import java.awt.BorderLayout;
import java.awt.image.BufferedImage;

import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Util {

	/**
	 * Display the bufferedImage
	 * 
	 * @param bufferedImage
	 */
	public static final void showImage(BufferedImage bufferedImage) {
		JFrame frame = new JFrame();
		JLabel label = new JLabel(new ImageIcon(bufferedImage));
		frame.getContentPane().add(label, BorderLayout.CENTER);
		frame.pack();
		frame.setVisible(true);
	}

	@SuppressWarnings("unused")
	private static final Logger LOGGER = LoggerFactory.getLogger(Util.class);
}
