/* Copyright (c) 2012 Jesper Öqvist <jesper@llbit.se>
 *
 * This file is part of Chunky.
 *
 * Chunky is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Chunky is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with Chunky.  If not, see <http://www.gnu.org/licenses/>.
 */
package se.llbit.chunky.ui;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;

import javax.swing.GroupLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.GroupLayout.Alignment;
import javax.swing.LayoutStyle.ComponentPlacement;

import org.apache.log4j.Logger;

import se.llbit.chunky.main.Chunky;
import se.llbit.util.ProgramProperties;

/**
 * @author Jesper Öqvist <jesper@llbit.se>
 */
@SuppressWarnings("serial")
public class WorldDirectoryPicker extends JDialog {
	
	private static final Logger logger =
			Logger.getLogger(WorldDirectoryPicker.class);
	
	private File selectedDirectory;
	private boolean accepted = false;
	
	/**
	 * Constructor
	 * @param parent 
	 */
	public WorldDirectoryPicker(JFrame parent) {
		super(parent, "World Directory Picker");
		
		setModalityType(ModalityType.APPLICATION_MODAL);
		
		JLabel lbl = new JLabel("Please select the directory where your Minecraft worlds are stored:");
		
		final JTextField scenePath = new JTextField(40);
		if (ProgramProperties.containsKey("worldDirectory")) {
			selectedDirectory = new File(ProgramProperties.getProperty("worldDirectory"));
		}
		
		if (!isValidSelection(selectedDirectory)) {
			selectedDirectory = Chunky.getSavesDirectory();
		}
		scenePath.setText(selectedDirectory.getAbsolutePath());
		
		JButton browseBtn = new JButton("Browse...");
		browseBtn.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				JFileChooser fileChooser = new JFileChooser(selectedDirectory);
				fileChooser.setDialogTitle("Select Scene Directory");
				fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
				int result = fileChooser.showOpenDialog(null);
				if (result == JFileChooser.APPROVE_OPTION) {
					selectedDirectory = fileChooser.getSelectedFile();
					scenePath.setText(selectedDirectory.getAbsolutePath());
				}
			}
		});
		
		JButton cancelBtn = new JButton("Cancel");
		cancelBtn.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				WorldDirectoryPicker.this.dispose();
			}
		});
		
		JButton okBtn = new JButton("OK");
		okBtn.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent arg0) {
				if (!isValidSelection(selectedDirectory)) {
					logger.warn("Please select a valid directory!");
				} else {
					ProgramProperties.setProperty("worldDirectory",
							selectedDirectory.getAbsolutePath());
					ProgramProperties.saveProperties();
					accepted = true;
					WorldDirectoryPicker.this.dispose();
				}
			}
		});
		
		JPanel panel = new JPanel();
		GroupLayout layout = new GroupLayout(panel);
		panel.setLayout(layout);
		layout.setHorizontalGroup(
			layout.createSequentialGroup()
				.addContainerGap()
				.addGroup(layout.createParallelGroup(Alignment.LEADING)
					.addComponent(lbl)
					.addGroup(layout.createSequentialGroup()
						.addComponent(scenePath)
						.addPreferredGap(ComponentPlacement.RELATED)
						.addComponent(browseBtn))
					.addGroup(layout.createSequentialGroup()
						.addPreferredGap(ComponentPlacement.RELATED, GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
						.addComponent(okBtn)
						.addPreferredGap(ComponentPlacement.RELATED)
						.addComponent(cancelBtn)))
				.addContainerGap()
		);
		layout.setVerticalGroup(
			layout.createParallelGroup(Alignment.LEADING)
			.addGroup(layout.createSequentialGroup()
				.addContainerGap()
				.addComponent(lbl)
				.addPreferredGap(ComponentPlacement.UNRELATED)
				.addGroup(layout.createParallelGroup()
					.addComponent(scenePath)
					.addComponent(browseBtn))
				.addPreferredGap(ComponentPlacement.UNRELATED)
				.addGroup(layout.createParallelGroup()
					.addComponent(okBtn)
					.addComponent(cancelBtn))
				.addContainerGap())
		);
		setContentPane(panel);
		pack();
		
		setLocationRelativeTo(parent);
	}

	/**
	 * @return The selected world saves directory
	 */
	public File getSelectedDirectory() {
		return selectedDirectory;
	}
	
	/**
	 * @return <code>true</code> if the OK button was clicked
	 */
	public boolean isAccepted() {
		return accepted;
	}

    /**
     * Ask user for the Minecraft world saves directory.
     * @param parent
     * @return The selected world directory, or <code>null</code> if the
     * user did not pick a valid world directory.
     */
    public static File getWorldDirectory(JFrame parent) {
    	File worldDir;
    	if (ProgramProperties.containsKey("worldDirectory")) {
    		worldDir = new File(ProgramProperties.getProperty("worldDirectory"));
    	} else {
			worldDir = Chunky.getSavesDirectory();
    	}
    	
    	if (!isValidSelection(worldDir)) {
    		WorldDirectoryPicker sceneDirPicker =
    				new WorldDirectoryPicker(parent);
    		sceneDirPicker.setVisible(true);
    		if (!sceneDirPicker.isAccepted())
    			return null;
    		worldDir = sceneDirPicker.getSelectedDirectory();
    	}
    	
    	if (isValidSelection(worldDir))
    		return worldDir;
    	else
    		return null;
	}
    
    private static boolean isValidSelection(File worldDir) {
    	return worldDir != null && worldDir.exists() && worldDir.isDirectory();
    }

}