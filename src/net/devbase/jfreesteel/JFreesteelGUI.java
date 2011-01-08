/*
 * jfreesteelgui: Serbian eID Viewer GUI Application (GNU AGPLv3)
 * Copyright (C) 2011 Goran Rakic
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License version
 * 3.0 as published by the Free Software Foundation.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with this software; if not, see
 * http://www.gnu.org/licenses/.
 */
package net.devbase.jfreesteel;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.TerminalFactory;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;

import net.devbase.jfreesteel.lib.EidCard;
import net.devbase.jfreesteel.lib.EidInfo;
import net.devbase.jfreesteel.lib.Reader;
import net.devbase.jfreesteel.lib.Reader.ReaderListener;
import net.devbase.jfreesteel.lib.gui.GUIPanel;

/**
 * JFreesteelGUI is singleton class behind jfreesteelgui application
 * 
 * @author Goran Rakic <grakic@devbase.net>
 */
public class JFreesteelGUI extends JPanel implements ReaderListener {
	
	private static final long serialVersionUID = -2497143822816312498L;

	private static final ResourceBundle bundle = ResourceBundle.getBundle("net.devbase.jfreesteel.jfreesteelgui");

	EidCard card = null;
	EidInfo info = null;
	Image photo  = null;
	
	GUIPanel details;

	private static JFreesteelGUI instance = null;
	
	public JFreesteelGUI()
	{
		setSize(new Dimension(720, 350));
		setLayout(new CardLayout(0, 0));

        /* Create "insert card" splash screen */
		JPanel splash = new JPanel();
		splash.setBackground(Color.WHITE);
		splash.setLayout(new GridBagLayout());
        ImageIcon insertCardIcon = new ImageIcon(JFreesteelGUI.class.getResource("/net/devbase/jfreesteel/lib/gui/smart-card-reader2.jpg"));
        JLabel label = new JLabel(bundle.getString("InsertCard"), insertCardIcon, SwingConstants.CENTER);
        label.setFont(label.getFont().deriveFont(label.getFont().getStyle() | Font.BOLD, label.getFont().getSize() + 4f));
        splash.add(label, new GridBagConstraints());

        add(splash, "splash");
        

        /*
		FIXME: card.debugEidInfo() is blocking on exclusive..

		MyGUIPanel is extending toolbar with new button... but this is not working

        class MyGUIPanel extends GUIPanel {

        	private static final long serialVersionUID = 1L;

			public MyGUIPanel()
			{
				super();
		        JButton button = new JButton("Debug");
		        button.setPreferredSize(new Dimension(130, 36));
		        button.setSize(new Dimension(200, 0));
		        button.addActionListener(new ActionListener() {
					
					@Override
					public void actionPerformed(ActionEvent e) {
						
						try {
							JOptionPane.showMessageDialog(MyGUIPanel.this, card.debugEidInfo());
						} catch (HeadlessException e1) {
							e1.printStackTrace();
						} catch (CardException e1) {
							e1.printStackTrace();
						}
						
					}
				});
		        toolbar.add(button, BorderLayout.WEST);
			}        	
        }         */


		/* Add card details screen */
        details = new GUIPanel();
        add(details, "details");
	}

	public static JFreesteelGUI getInstance()
	{
		if(instance == null) {
			instance = new JFreesteelGUI();
		}
		return instance;
	}
    
    public static void main(String[] args)
    {
        javax.swing.SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                createAndShowGUI();
            }
        });
    }

    /**
     * Create the GUI and show it.
     */
    private static void createAndShowGUI()
    {
        // Enable font anti aliasing
        System.setProperty("awt.useSystemAAFontSettings","on");
        System.setProperty("swing.aatext", "true");
        
        // Set sr_RS locale as default
        Locale.setDefault(new Locale("sr", "RS"));
//        dateFormat = DateFormat.getDateInstance(DateFormat.DEFAULT, Locale.getDefault());
        
    	// Create and set up the window
        JFrame frame = new JFrame(bundle.getString("FreesteelTitle"));
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLocationRelativeTo(null);

        // Set default look and feel
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame,
            		bundle.getString("GUIError") + ": " + e.getMessage(),
            		bundle.getString("GUIErrorTitle"),
                    JOptionPane.WARNING_MESSAGE);
            e.printStackTrace();
        }
        
        // Test for Java 1.6
        if(!System.getProperty("java.version").startsWith("1.6"))
        {
            JOptionPane.showMessageDialog(frame,
            		bundle.getString("JavaError"),
            		bundle.getString("JavaErrorTitle"),
                    JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
        	
        // Get the list of terminals
        CardTerminal terminal = null;
        try {
			TerminalFactory factory = TerminalFactory.getDefault();
			terminal = pickTerminalGUI(frame, factory.terminals().list());

        } catch (Exception e) {
			JOptionPane.showMessageDialog(frame,
					bundle.getString("ReaderError") + ": " + e.getMessage(),
					bundle.getString("ReaderErrorTitle"),
				    JOptionPane.ERROR_MESSAGE);
			e.printStackTrace();
			System.exit(1);
		}

        // Create and set up the content pane
        JFreesteelGUI gui = JFreesteelGUI.getInstance();
        frame.getContentPane().add(gui, BorderLayout.CENTER);
        frame.pack();

        // Create reader and add GUI as the listener
        Reader reader = new Reader(terminal);
        reader.addCardListener(gui);

        // Display the window
        frame.setVisible(true);
    }    

    public static CardTerminal pickTerminalGUI(JFrame frame, List<CardTerminal> terminals)
    {
    	if(terminals.size() == 1) return terminals.get(0);
    	
    	CardTerminal terminal = (CardTerminal)JOptionPane.showInputDialog(frame,
    									bundle.getString("SelectReader"),
    									bundle.getString("SelectReaderTitle"),
										JOptionPane.PLAIN_MESSAGE,
										null,
										terminals.toArray(),
										terminals.get(0));

    	// Cancel clicked
    	if(terminal == null) System.exit(1);

    	return terminal;
	}

    private void showCardError(Exception e)
    {
		JOptionPane.showMessageDialog(this,
				bundle.getString("CardError") + ": " + e.getMessage(),
				bundle.getString("CardErrorTitle"),
			    JOptionPane.ERROR_MESSAGE);
		e.printStackTrace();
    }
    
	public void inserted(final EidCard card)
	{
		System.out.println("Card inserted");
		CardLayout cl = (CardLayout) this.getLayout();
	    cl.show(this, "details");		
		
	    try {
			this.card = card;
			
			info = card.readEidInfo();
			details.setDetails(info);

			photo = card.readEidPhoto();			
			details.setPhoto(photo);
			
/*
 			// TODO: Debug this, locate where does deadlock happen and fix it!

			card.readEidPhotoAsync(new EidPhotoAsyncCallback() {
				public void ready(Image image)
				{
					details.setPhoto(image);
				}

				public void error(CardException e)
				{
					showCardError(e);
				}
			});
*/			
			
		} catch (CardException e) {
			showCardError(e);
		}
	}

	public void removed()
	{
		System.out.println("Card removed");

		CardLayout cl = (CardLayout) this.getLayout();
	    cl.show(this, "splash");
	    
		card = null;
		info = null;
		photo = null;
	    details.clearDetailsAndPhoto();
	}

}
