import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.Socket;
import java.net.URL;
import java.net.UnknownHostException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.filechooser.FileFilter;
import javax.xml.parsers.*;
import javax.xml.stream.*;
import javax.xml.stream.FactoryConfigurationError;

import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

@SuppressWarnings("serial")
class PuzzleGUI extends JFrame {

	/**
	 * Start the program
	 * 
	 * @param args
	 */
	public static void main(String[] args) {
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				new PuzzleGUI();
			}
		});
	}

	public static final String CW_EXT = "cw";// CrossWord
	public static final String CW_SAVE_EXT = "cws";// CrossWord Save
	private List<Crossword> crosswords;
	private Crossword currentCrossword;
	private Cell[][] puzzle;
	private CrosswordGrid grid;
	private JLabel crosswordTitle;
	@SuppressWarnings("rawtypes")
	private JList acrossJList, downJList;
	private JTextArea logArea;
	private String name;
	private JFrame window;
	private boolean solvedSupport;

	private Socket socket = null;
	private PrintWriter out = null;
	private BufferedReader in = null;
	private boolean connected;
	Thread input;

	public PuzzleGUI() {
		super("Crossword Puzzle");
		initGUI();
	}

	/**
	 * Initialise all GUI components
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private void initGUI() {
		// setup before initialise crossword, because these items are accessed in there
		acrossJList = new JList();
		acrossJList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		acrossJList.setCellRenderer(new ClueRenderer());
		acrossJList.addListSelectionListener(new ListSelectionListener() {

			@Override
			public void valueChanged(ListSelectionEvent e) {
				if (!((JList) e.getSource()).isSelectionEmpty()) {
					int selected = ((JList) e.getSource()).getSelectedIndex();
					Clue clue = currentCrossword.acrossClues.get(selected);
					grid.onlyHighlightClue(clue.x, clue.y, clue.number, CrosswordGrid.ACROSS);
				}
			}
		});

		downJList = new JList();
		downJList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		downJList.setCellRenderer(new ClueRenderer());
		downJList.addListSelectionListener(new ListSelectionListener() {

			@Override
			public void valueChanged(ListSelectionEvent e) {
				if (!((JList) e.getSource()).isSelectionEmpty()) {
					int selected = ((JList) e.getSource()).getSelectedIndex();
					Clue clue = currentCrossword.downClues.get(selected);
					grid.onlyHighlightClue(clue.x, clue.y, clue.number, CrosswordGrid.DOWN);
				}
			}
		});
		crosswordTitle = new JLabel("", SwingConstants.CENTER);

		initialiseCrosswords();
		window = this;

		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

		JPanel crosswordPanel = new JPanel();
		crosswordPanel.setLayout(new BoxLayout(crosswordPanel, BoxLayout.X_AXIS));
		JPanel gridPanel = new JPanel(new BorderLayout(10, 10));
		gridPanel.add(crosswordTitle, BorderLayout.NORTH);
		grid = new CrosswordGrid(puzzle);
		gridPanel.add(grid, BorderLayout.CENTER);
		crosswordPanel.add(gridPanel);

		JPanel cluePanel = new JPanel(new GridLayout(2, 1, 5, 5));
		cluePanel.setPreferredSize(new Dimension(220, 200));

		JPanel acrossCluesPanel = new JPanel(new BorderLayout());
		acrossCluesPanel.add(new JLabel("Across Clues", SwingConstants.CENTER), BorderLayout.NORTH);
		acrossCluesPanel.add(new JScrollPane(acrossJList), BorderLayout.CENTER);

		JPanel downCluesPanel = new JPanel(new BorderLayout());
		downCluesPanel.add(new JLabel("Down Clues", SwingConstants.CENTER), BorderLayout.NORTH);
		downCluesPanel.add(new JScrollPane(downJList), BorderLayout.CENTER);

		cluePanel.add(acrossCluesPanel);
		cluePanel.add(downCluesPanel);
		crosswordPanel.add(cluePanel);
		panel.add(crosswordPanel);

		JPanel textPanel = new JPanel(new BorderLayout());

		JPanel chatPanel = new JPanel();
		chatPanel.add(new JLabel("Chat:"));
		final JTextField chatField = new JTextField(30);
		chatField.addKeyListener(new KeyAdapter() {

			@Override
			public void keyPressed(KeyEvent e) {
				if (e.getKeyCode() == KeyEvent.VK_ENTER) {
					if (out != null)
						out.println("chat:" + name + ":" + chatField.getText());
					Date now = new Date();
					SimpleDateFormat formatter = new SimpleDateFormat("dd/MM/yyyy HH:mm");
					formatter.format(now);
					logArea.append(formatter.format(now) + "\n\t" + name + " says: " + chatField.getText() + "\n");
					chatField.setText("");
				}
			}
		});
		chatPanel.add(chatField);
		textPanel.add(chatPanel, BorderLayout.NORTH);

		logArea = new JTextArea();
		logArea.setEditable(false);
		JScrollPane textAreaPanel = new JScrollPane(logArea);
		textAreaPanel.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
		textPanel.add(textAreaPanel, BorderLayout.CENTER);
		textPanel.setPreferredSize(new Dimension(500, 140));
		textPanel.setMinimumSize(new Dimension(500, 400));
		textPanel.setMaximumSize(new Dimension(2000, 400));

		panel.add(textPanel);

		setContentPane(panel);
		// setup menubar
		JMenuBar menuBar = createMenuBar();
		setJMenuBar(menuBar);
		setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		// setPreferredSize(new Dimension(600, 600));
		setMinimumSize(new Dimension(600, 550));
		pack();
		setVisible(true);
		// TODO fix - only temp mod to fix annoyance
		// name = "ted";
		do {
			setUser();
			if (name == null)
				JOptionPane.showMessageDialog(window, "Must enter a name", "Error", JOptionPane.ERROR_MESSAGE);
		} while (name == null);
	}

	private JMenuBar createMenuBar() {
		// setup menubar
		JMenuBar menuBar = new JMenuBar();

		JMenu fileMenu = new JMenu("File");

		JMenuItem loadProgress = new JMenuItem();
		loadProgress.setAction(new AbstractAction("Open Saved Game") {
			@Override
			public void actionPerformed(ActionEvent e) {
				String[] extensionAllowed = { CW_SAVE_EXT };
				File file = CrosswordIO.getFile(extensionAllowed, true);
				if (file == null) // no file selected
					return;
				Crossword c = CrosswordIO.readPuzzle(file);
				if (c != null) {
					addCrossword(c);
					loadCrossword(c);
				} else {
					JOptionPane.showMessageDialog(window, "Error occurred while reading the file", "Error",
							JOptionPane.ERROR_MESSAGE);
				}
			}
		});
		loadProgress.setMnemonic(KeyEvent.VK_O);
		loadProgress.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, ActionEvent.CTRL_MASK));
		fileMenu.add(loadProgress);

		JMenuItem saveProgress = new JMenuItem();
		saveProgress.setAction(new AbstractAction("Save Game") {
			@Override
			public void actionPerformed(ActionEvent e) {
				String[] extensionAllowed = { CW_SAVE_EXT };
				File file = CrosswordIO.getFile(extensionAllowed, false);
				if (file == null) // no file selected
					return;
				CrosswordIO.writePuzzle(file, currentCrossword);
			}
		});
		saveProgress.setMnemonic(KeyEvent.VK_S);
		saveProgress.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, ActionEvent.CTRL_MASK));
		fileMenu.add(saveProgress);

		fileMenu.addSeparator();

		JMenuItem resetCrossword = new JMenuItem();
		resetCrossword.setAction(new AbstractAction("Reset Crossword") {
			@Override
			public void actionPerformed(ActionEvent e) {
				if (currentCrossword != null) {
					currentCrossword.resetCrossword();

					for (Cell[] cellarr : puzzle)
						for (Cell cell : cellarr)
							if (cell != null)
								cell.c = "";
					repaint();
				}
			}
		});
		resetCrossword.setMnemonic(KeyEvent.VK_R);
		resetCrossword.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_R, ActionEvent.CTRL_MASK));
		fileMenu.add(resetCrossword);

		JMenuItem loadCrossword = new JMenuItem();
		loadCrossword.setAction(new AbstractAction("Load Crossword") {
			@Override
			public void actionPerformed(ActionEvent e) {
				Crossword c = getCrossword();
				if (c != null)
					loadCrossword(c);
			}
		});
		loadCrossword.setMnemonic(KeyEvent.VK_L);
		loadCrossword.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_L, ActionEvent.CTRL_MASK));
		fileMenu.add(loadCrossword);

		fileMenu.addSeparator();

		JMenuItem importCrossword = new JMenuItem();
		importCrossword.setAction(new AbstractAction("Import Crossword") {
			@Override
			public void actionPerformed(ActionEvent e) {
				String[] extensionAllowed = { CW_EXT };
				File file = CrosswordIO.getFile(extensionAllowed, true);
				if (file == null) // no file selected
					return;
				Crossword c = CrosswordIO.importPuzzle(file);
				if (c != null)
					addCrossword(c);
				else
					JOptionPane.showMessageDialog(window, "Error occurred while reading the file", "Error",
							JOptionPane.ERROR_MESSAGE);
			}
		});
		importCrossword.setMnemonic(KeyEvent.VK_I);
		importCrossword.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_I, ActionEvent.CTRL_MASK));
		fileMenu.add(importCrossword);

		JMenuItem exportCrossword = new JMenuItem();
		exportCrossword.setAction(new AbstractAction("Export Crossword") {
			@Override
			public void actionPerformed(ActionEvent e) {
				String[] extensionAllowed = { CW_EXT };
				Crossword c = getCrossword();
				File file = CrosswordIO.getFile(extensionAllowed, false);
				if (file == null) // no file selected
					return;
				if (c != null)
					CrosswordIO.exportPuzzle(file, c);
			}
		});
		exportCrossword.setMnemonic(KeyEvent.VK_E);
		exportCrossword.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_E, ActionEvent.CTRL_MASK));
		fileMenu.add(exportCrossword);

		fileMenu.addSeparator();

		JMenuItem closeWindow = new JMenuItem();
		closeWindow.setAction(new AbstractAction("Close") {
			@Override
			public void actionPerformed(ActionEvent e) {
				WindowEvent wev = new WindowEvent(window, WindowEvent.WINDOW_CLOSING);
				Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(wev);
			}
		});
		closeWindow.setMnemonic(KeyEvent.VK_Q);
		closeWindow.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Q, ActionEvent.CTRL_MASK));
		fileMenu.add(closeWindow);

		menuBar.add(fileMenu);

		JMenu optionsMenu = new JMenu("Options");

		JMenuItem setUser = new JMenuItem();
		setUser.setAction(new AbstractAction("Set User") {
			@Override
			public void actionPerformed(ActionEvent e) {
				setUser();
			}
		});
		setUser.setMnemonic(KeyEvent.VK_U);
		setUser.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_U, ActionEvent.CTRL_MASK));
		optionsMenu.add(setUser);

		JCheckBoxMenuItem toggleSolvedSupport = new JCheckBoxMenuItem();
		toggleSolvedSupport.setAction(new AbstractAction("Solved Help") {
			@Override
			public void actionPerformed(ActionEvent e) {
				solvedSupport = !solvedSupport;
				window.repaint();
			}
		});
		toggleSolvedSupport.setMnemonic(KeyEvent.VK_H);
		toggleSolvedSupport.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_H, ActionEvent.CTRL_MASK));
		optionsMenu.add(toggleSolvedSupport);

		JMenuItem anagram = new JMenuItem();
		final SpinnerModel numWordsModel = new SpinnerNumberModel(1, // initial value
				1, // min
				6, // max
				1); // step
		final JSpinner numWordsSpinner = new JSpinner(numWordsModel);
		anagram.setAction(new AbstractAction("Anagrams") {
			@Override
			public void actionPerformed(ActionEvent e) {
				// easy anagram using a web page
				String word = JOptionPane.showInputDialog(window, "Word or phrase:");
				if (word == null || word.equals(""))
					return;
				word = word.replaceAll("[^a-zA-Z ]", "");
				if (word.equals(""))
					return;
				word = word.replaceAll(" ", "+");
				int option = JOptionPane.showOptionDialog(window, numWordsSpinner, "Number Words",
						JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, null, null, null);
				int numWords;
				if (option == JOptionPane.OK_OPTION) {
					numWords = ((Integer) numWordsModel.getValue());
				} else {
					return;
				}
				numWordsSpinner.setValue(1);
				try {
					String address = "http://www.ssynth.co.uk/~gay/cgi-bin/nph-an?line=" + word + "&words=" + numWords
							+ "&dict=antworth&doai=on";
					URL webpage = new URL(address);

					BufferedReader readPage = new BufferedReader(new InputStreamReader(webpage.openStream()));
					String line = "";
					while (!(line = readPage.readLine()).contains("<pre>"))
						;
					ArrayList<String> anagrams = new ArrayList<String>();
					if (!line.contains("</pre>")) {
						line = line.replaceAll("<pre>", "");
						anagrams.add(line);
						boolean loop = true;
						while ((line = readPage.readLine()) != null && loop) {
							if (line.contains("</pre>"))
								break;
							anagrams.add(line);
						}
					}
					JList list = new JList(anagrams.toArray());
					JScrollPane pane = new JScrollPane(list);
					pane.setPreferredSize(new Dimension(160, 200));
					list.setLayoutOrientation(JList.VERTICAL);
					list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
					JOptionPane.showMessageDialog(window, pane, "Anagrams", JOptionPane.PLAIN_MESSAGE);

				} catch (IOException ex) {
					ex.printStackTrace();
					JOptionPane.showMessageDialog(window, "A problem occured, possibly no internet", "Error",
							JOptionPane.ERROR_MESSAGE);
				}
			}
		});
		anagram.setMnemonic(KeyEvent.VK_A);
		anagram.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_A, ActionEvent.CTRL_MASK));
		optionsMenu.add(anagram);

		menuBar.add(optionsMenu);

		JMenu networkingMenu = new JMenu("Networking");
		// TODO netwworking
		final JMenuItem connect = new JMenuItem();
		connect.setAction(new AbstractAction("Connect") {
			@Override
			public void actionPerformed(ActionEvent e) {
				if (connected) {
					try {
						socket.close();
						out.close();
						in.close();
					} catch (IOException ex) {
						System.err.println("Couldn't get I/O for " + "the connection to: taranis.");
					}
					connect.setText("Connect");
					connected = false;
				} else {
					try {
						socket = new Socket("linuxproj.ecs.soton.ac.uk", 1292);
						out = new PrintWriter(socket.getOutputStream(), true);
						in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
						connected = true;
						input = new Thread(new InStream());
						input.start();
						connect.setText("Disconnect");
					} catch (UnknownHostException ex) {
						JOptionPane.showMessageDialog(window, "Host server inaccessible at \n"
								+ "the moment. Try setting new host", "Error", JOptionPane.ERROR_MESSAGE);
					} catch (IOException ex) {
						JOptionPane.showMessageDialog(window, "IO for host failed", "Error", JOptionPane.ERROR_MESSAGE);
					}
				}
			}
		});
		networkingMenu.add(connect);

		JMenuItem changeHost = new JMenuItem();
		changeHost.setAction(new AbstractAction("Change Host") {
			@Override
			public void actionPerformed(ActionEvent e) {
				if (connected) {
					try {
						socket.close();
						out.close();
						in.close();
					} catch (IOException ex) {
						JOptionPane.showMessageDialog(window, "IO for host failed", "Error", JOptionPane.ERROR_MESSAGE);
					}
					connect.setText("Connect");
					connected = false;
				}
				try {
					String host = JOptionPane.showInputDialog(window, "Enter Host Address:", "Change Host",
							JOptionPane.PLAIN_MESSAGE);
					socket = new Socket(host, 1292);
					out = new PrintWriter(socket.getOutputStream(), true);
					in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
					connected = true;
					input = new Thread(new InStream());
					input.start();
					connect.setText("Disconnect");
				} catch (UnknownHostException ex) {
					JOptionPane.showMessageDialog(window, "Host server inaccessible at \n"
							+ "the moment. Try setting new host", "Error", JOptionPane.ERROR_MESSAGE);
				} catch (IOException ex) {
					JOptionPane.showMessageDialog(window, "IO for host failed", "Error", JOptionPane.ERROR_MESSAGE);
				}
			}
		});
		networkingMenu.add(changeHost);

		menuBar.add(networkingMenu);

		return menuBar;
	}

	class InStream implements Runnable {
		public void run() {
			String line = "";
			System.out.println("waiting");
			try {
				while ((line = in.readLine()) != null) {
					System.out.println(line);
					String[] vals = line.split(":");
					if (!vals[0].equals("chat")) {
						int x = Integer.parseInt(vals[0]);
						int y = Integer.parseInt(vals[1]);
						char c = vals[2].charAt(0);
						String username = vals[3];
						grid.setCell(x, y, c, username);
					} else {
						Date now = new Date();
						SimpleDateFormat formatter = new SimpleDateFormat("dd/MM/yyyy HH:mm");
						formatter.format(now);
						logArea.append(formatter.format(now) + "\n\t" + vals[1] + " says: " + vals[2] + "\n");
					}
				}
				connected = false;
			} catch (IOException e) {
			}
		}
	}

	private void outStream(int x, int y, char c, String username) {
		String line = "";
		line += Integer.toString(x);
		line += ":";
		line += Integer.toString(y);
		line += ":";
		line += Character.toString(c);
		line += ":";
		line += username;
		out.println(line);
	}

	private boolean addCrossword(Crossword c) {
		if (c != null && !crosswords.contains(c))
			return crosswords.add(c);
		else
			return false;
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private Crossword getCrossword() {
		JList list = new JList(crosswords.toArray());
		JScrollPane pane = new JScrollPane(list);
		pane.setPreferredSize(new Dimension(160, 200));
		list.setLayoutOrientation(JList.VERTICAL);
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		int option = JOptionPane.showOptionDialog(window, pane, "Choose Crossword", JOptionPane.OK_CANCEL_OPTION,
				JOptionPane.PLAIN_MESSAGE, null, null, null);
		if (option == JOptionPane.OK_OPTION)
			return (Crossword) list.getSelectedValue();
		else
			return null;

	}

	private void setUser() {
		String option = JOptionPane.showInputDialog(window, "Name: ");
		// ignore cancel or empty string
		if (option != null && !option.equals("")) {
			name = option;
			Date now = new Date();
			SimpleDateFormat formatter = new SimpleDateFormat("dd/MM/yyyy HH:mm");

			logArea.append(formatter.format(now) + "\n\tCurrent user: " + name + "\n");
		}
	}

	private void initialiseCrosswords() {
		crosswords = new ArrayList<Crossword>();
		ArrayList<Clue> acrossClues = new ArrayList<Clue>();
		ArrayList<Clue> downClues = new ArrayList<Clue>();

		// @formatter:off
		acrossClues = new ArrayList<Clue>();
		downClues = new ArrayList<Clue>();
		
		acrossClues.add(new Clue(1, 1, 0, "Eager Involvement", "enthusiasm"));		acrossClues.add(new Clue(8, 0, 2, "Stream of water", "river"));		acrossClues.add(new Clue(9, 6, 2, "Take as one's own", "adopt"));		acrossClues.add(new Clue(10, 0, 4, "Ball game", "golf"));
		acrossClues.add(new Clue(12, 5, 4, "Guard", "sentry"));		acrossClues.add(new Clue(14, 0, 6, "Language communication", "speech"));		acrossClues.add(new Clue(17, 7, 6, "Fruit", "plum"));		acrossClues.add(new Clue(21, 0, 8, "In addition", "extra"));
		acrossClues.add(new Clue(22, 6, 8, "Boundary", "limit"));		acrossClues.add(new Clue(23, 0, 10, "Executives", "management"));
		
		downClues.add(new Clue(2, 2, 0, "Pertaining to warships", "naval"));		downClues.add(new Clue(3, 4, 0, "Solid", "hard"));		downClues.add(new Clue(4, 6, 0, "Apportion", "share"));		downClues.add(new Clue(5, 8, 0, "Concerning", "about"));
		downClues.add(new Clue(6, 10, 0, "Friendly", "matey"));		downClues.add(new Clue(7, 0, 1, "Boast", "brag"));		downClues.add(new Clue(11, 3, 4, "Enemy", "foe"));		downClues.add(new Clue(13, 7, 4, "Doze", "nap"));
		downClues.add(new Clue(14, 0, 6, "Water vapour", "steam"));		downClues.add(new Clue(15, 2, 6, "Consumed", "eaten"));		downClues.add(new Clue(16, 4, 6, "Loud, resonant sound", "clang"));		downClues.add(new Clue(18, 8, 6, "Yellowish, citrus fruit", "lemon"));
		downClues.add(new Clue(19, 10, 6, "Mongrel dog", "mutt"));		downClues.add(new Clue(20, 6, 7, "Shut with force", "slam"));
		
		crosswords.add(new Crossword("An Example Crossword", 11, acrossClues, downClues));
		
		acrossClues = new ArrayList<Clue>();
		downClues = new ArrayList<Clue>();
		
		acrossClues.add(new Clue(1, 1, 0, "Showy", "OSTENTATIOUS"));		acrossClues.add(new Clue(9, 0, 2, "Carrying weapons", "ARMED"));		acrossClues.add(new Clue(10, 6, 2, "Cocaine (anag)", "OCEANIC"));		acrossClues.add(new Clue(11, 0, 4, "Dull continuous pain", "ACHE"));
		acrossClues.add(new Clue(12, 5, 4, "Under an obligation", "BEHOLDEN"));		acrossClues.add(new Clue(14, 0, 6, "Cheap and showy", "TAWDRY"));		acrossClues.add(new Clue(15, 7, 6, "Bewail", "LAMENT"));		acrossClues.add(new Clue(18, 0, 8, "Contrary", "OPPOSITE"));
		acrossClues.add(new Clue(20, 9, 8, "Sign of things to come", "OMEN"));		acrossClues.add(new Clue(22, 0, 10, "Impetuous person", "HOTHEAD"));		acrossClues.add(new Clue(23, 8, 10, "Norwegian dramatist", "IBSEN"));		acrossClues.add(new Clue(24, 0, 12, "Rebuff", "COLD-SHOULDER"));
	
		downClues.add(new Clue(2, 2, 0, "One way or another", "SOMEHOW"));		downClues.add(new Clue(3, 4, 0, "Swirling current", "EDDY"));		downClues.add(new Clue(4, 6, 0, "Gardener's tool", "TROWEL"));		downClues.add(new Clue(5, 8, 0, "Sacred writings of Islam", "THE KORAN"));
		downClues.add(new Clue(6, 10, 0, "Possessed", "OWNED"));		downClues.add(new Clue(7, 12, 0, "Best", "SECOND TO NONE"));		downClues.add(new Clue(8, 0, 1, "Disastrous", "CATASTROPHIC"));		downClues.add(new Clue(13, 4, 5, "European Commission HQ", "BRUSSELS"));
		downClues.add(new Clue(16, 10, 6, "All together", "EN MASSE"));		downClues.add(new Clue(17, 6, 7, "Artist's workroom", "STUDIO"));		downClues.add(new Clue(19, 2, 8, "Part of a flower ", "PETAL"));		downClues.add(new Clue(21, 8, 9, "English philosopher and economist, d. 1873", "MILL"));
		
		crosswords.add(new Crossword("Guardian 13,019", 13, acrossClues, downClues));

		currentCrossword = crosswords.get(1);
		loadCrossword(crosswords.get(1));
		// @formatter:on
	}

	@SuppressWarnings("rawtypes")
	class ClueRenderer extends DefaultListCellRenderer {

		public ClueRenderer() {
			setOpaque(true);
		}

		public Component getListCellRendererComponent(JList list, Object value, int index, boolean isSelected,
				boolean cellHasFocus) {
			super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);

			if (solvedSupport && ((Clue) value).solved)
				setBackground(new Color(151, 206, 139));
			if (isSelected)
				setBorder(BorderFactory.createLineBorder(new Color(99, 130, 191)));

			return this;
		}

	}

	@SuppressWarnings("unchecked")
	private void loadCrossword(Crossword c) {
		currentCrossword.resetCrossword();
		currentCrossword = c;
		crosswordTitle.setText(c.title);
		puzzle = new Cell[currentCrossword.size][currentCrossword.size];

		acrossJList.setListData(currentCrossword.acrossClues.toArray());
		downJList.setListData(currentCrossword.downClues.toArray());

		for (Clue clue : currentCrossword.acrossClues)
			loadClue(clue, true);
		for (Clue clue : currentCrossword.downClues)
			loadClue(clue, false);
		if (grid != null) {// not first time
			grid.setPuzzle(puzzle);
		}
	}

	private void loadClue(Clue clue, boolean across) {
		char[] answer = clue.answer.replaceAll("(-| )", "").toUpperCase().toCharArray();
		char a = ' ';
		if (clue.isSolved())
			System.out.println(clue);
		if (clue.isSolved())
			a = answer[0];
		if (puzzle[clue.x][clue.y] == null)
			puzzle[clue.x][clue.y] = new Cell(a, answer[0], null, null);

		if (across) {
			puzzle[clue.x][clue.y].acrossClue = clue;
			// only if already empty, set to a
			if (puzzle[clue.x][clue.y].c.equals(" ") && clue.isSolved())
				puzzle[clue.x][clue.y].c = Character.toString(a);
		} else {
			puzzle[clue.x][clue.y].downClue = clue;
			// only if already empty, set to a
			if (puzzle[clue.x][clue.y].c.equals(" ") && clue.isSolved())
				puzzle[clue.x][clue.y].c = Character.toString(a);
		}
		puzzle[clue.x][clue.y].clueNum = Integer.toString(clue.number);
		for (int i = 1; i < answer.length; i++) {
			a = ' ';
			if (clue.isSolved())
				a = answer[i];
			if (across) { // check if it needs to go across or down
				// needed for cells which are for both across and down clues
				// System.out.println(clue.answer + " " + answer[i] + " " + clue.number + " "
				// + (clue.x + i) + ", " + clue.y);
				if (puzzle[clue.x + i][clue.y] == null)
					puzzle[clue.x + i][clue.y] = new Cell(a, answer[i], clue, null);
				else {
					puzzle[clue.x + i][clue.y].acrossClue = clue;
					if (puzzle[clue.x][clue.y].c.equals(" ") && clue.isSolved()) // only if already
																					// empty, set
																					// to a
						puzzle[clue.x][clue.y].c = Character.toString(a);
				}
			} else {
				// needed for cells which are for both across and down clues
				if (puzzle[clue.x][clue.y + i] == null)
					puzzle[clue.x][clue.y + i] = new Cell(a, answer[i], null, clue);
				else {
					puzzle[clue.x][clue.y + i].downClue = clue;
					if (puzzle[clue.x][clue.y].c.equals(" ") && clue.isSolved()) // only if already
																					// empty, set
																					// to a
						puzzle[clue.x][clue.y].c = Character.toString(a);
				}
			}
		}
	}

	class CrosswordGrid extends JPanel {
		private static final int NONE = 0, ACROSS = 1, DOWN = 2; // direction to highlight
		// direction to move selected cell (DOWN is already 2)
		private static final int UP = 0, LEFT = 1, RIGHT = 3;
		private final Color CELL_HIGHLIGHT_COLOR = new Color(3, 93, 179);
		private final Color CLUE_HIGHLIGHT_COLOR = new Color(82, 159, 225);
		BufferedImage buffImg;
		private Cell[][] puzzle;
		double cellWidth, prevCellWidth; // prevCellWidth for preventing recalculations
		Font font; // font to use to draw cell letters
		int xOffset, yOffset;
		int clueToHighlight, highlightDirection;
		Point cellToHighlight;

		public CrosswordGrid(Cell[][] puzzle) {
			this.puzzle = puzzle;
			setMinimumSize(new Dimension(400, 400));
			setFocusable(true);
			addMouseListener(new MouseAdapter() {
				@Override
				public void mousePressed(MouseEvent e) {
					Point p = coordToCell(e.getX(), e.getY());
					highlightCell(p.x, p.y);
				}
			});
			addKeyListener(new KeyAdapter() {

				@Override
				public void keyPressed(KeyEvent e) {
					switch (e.getKeyCode()) {
					case KeyEvent.VK_UP:
						move(UP);
						break;
					case KeyEvent.VK_BACK_SPACE:
						setCell(Character.toUpperCase(' '), false);
						break;
					case KeyEvent.VK_DOWN:
						move(DOWN);
						break;
					case KeyEvent.VK_LEFT:
						move(LEFT);
						break;
					case KeyEvent.VK_RIGHT:
						move(RIGHT);
						break;
					default:
						// a cell must be selected
						if (!cellToHighlight.equals(new Point(-1, -1))) {
							char c = Character.toUpperCase(e.getKeyChar());
							if (c >= 'A' && c <= 'Z')
								setCell(Character.toUpperCase(e.getKeyChar()));
						}
						break;
					}
				}

			});
			clueToHighlight = 0;
			cellToHighlight = new Point(-1, -1);
			highlightDirection = NONE;
			cellWidth = 0;
		}

		public void paint(Graphics gr) {
			xOffset = yOffset = 0; // reset to prevent incorrect displacement
			// to reduce calls to getHeight() and getWidth()
			int height = getHeight();
			int width = getWidth();
			// smallest dimension (height or width)
			int smallestDim = 0;
			if (width < getHeight()) {
				smallestDim = width;
				yOffset = (height - smallestDim) / 2;
			} else {
				smallestDim = height;
				xOffset = (width - smallestDim) / 2;
			}
			prevCellWidth = cellWidth;
			cellWidth = ((double) smallestDim) / ((double) puzzle.length);
			buffImg = new BufferedImage(smallestDim, smallestDim, BufferedImage.TYPE_INT_ARGB);
			drawGrid(buffImg);
			Graphics2D g = (Graphics2D) gr;
			g.setColor(new Color(238, 238, 238));
			g.fillRect(0, 0, width, height);
			g.drawImage(buffImg, xOffset, yOffset, smallestDim, smallestDim, null);
		}

		private void drawGrid(BufferedImage img) {
			Graphics2D g = (Graphics2D) img.getGraphics();
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_GASP);
			if (prevCellWidth != cellWidth) {
				int fontSize = (int) (cellWidth - 17); // roughly the right value to
				// start at
				FontMetrics fm;
				// increase font size till height is roughly 6 less than cell height
				do {
					++fontSize;
					g.setFont(new Font("arial", Font.BOLD, fontSize));
					fm = g.getFontMetrics();
					System.out.println(fm.getHeight() + " " + fontSize + " " + cellWidth);
				} while (fm.getHeight() < cellWidth - 6);
				font = g.getFont();
			} else {
				g.setFont(font);
			}
			g.setColor(Color.LIGHT_GRAY);
			g.fillRect(0, 0, img.getWidth(), img.getHeight());

			for (int x = 0; x < puzzle.length; x++)
				for (int y = 0; y < puzzle.length; y++)
					drawCell(puzzle[x][y], x, y, g);
		}

		private void drawCell(Cell cell, int x, int y, Graphics2D g) {
			int xCoord = (int) Math.round((x * cellWidth) + 1);
			int yCoord = (int) Math.round((y * cellWidth) + 1);
			int width = (int) Math.round(cellWidth - 2);
			if (cell == null) {
				g.setColor(Color.BLACK);
				g.fillRect(xCoord, yCoord, width, width);
			} else {
				if (cellToHighlight.equals(new Point(x, y)))
					g.setColor(CELL_HIGHLIGHT_COLOR);
				else if ((cell.hasAcross() && cell.acrossClue.number == clueToHighlight && highlightDirection == ACROSS)
						|| (cell.hasDown() && cell.downClue.number == clueToHighlight && highlightDirection == DOWN))
					g.setColor(CLUE_HIGHLIGHT_COLOR);
				else
					g.setColor(Color.WHITE);
				g.fillRect(xCoord, yCoord, width, width);
				g.setColor(Color.BLACK);
				FontMetrics fm = g.getFontMetrics();
				g.drawString(cell.c, (int) (xCoord + width / 2 - fm.getStringBounds(cell.c, g).getWidth() / 2),
						(int) (yCoord + fm.getHeight() / 2.2 + width / 2));
				if (cell.isStart()) {
					Font temp = g.getFont(); // to reset font afterwards
					g.setFont(new Font("Arial Narrow", Font.PLAIN, 9));
					g.drawString(cell.clueNum, xCoord + 1, yCoord + 7);
					g.setFont(temp);
				}
			}
		}

		private Point coordToCell(int x, int y) {
			x = (int) ((x - xOffset) / cellWidth);
			y = (int) ((y - yOffset) / cellWidth);
			return new Point(x, y);
		}

		private void highlightCell(int x, int y) {
			requestFocus();
			System.out.println("(" + x + "," + y + "), " + puzzle.length);
			if (!(x >= 0 && x < puzzle.length && y >= 0 && y < puzzle.length)) {
				this.cellToHighlight = new Point(-1, -1);
				highlightClue(null, NONE);
				repaint();
				requestFocus();
				return;
			}
			// TODO HORIBBLE CODE
			Cell cell = puzzle[x][y];
			if (cell != null) {
				Point cellHighlight = new Point(x, y);

				if (this.cellToHighlight.equals(cellHighlight)) { // clicked same cell again
					if (highlightDirection == ACROSS) {
						if (cell.hasDown()) {
							highlightClue(cell.downClue, DOWN);
							this.cellToHighlight = cellHighlight;
						} else {
							highlightNone();
						}
					} else if (highlightDirection == DOWN) {
						highlightNone();
					}
				} else { // clicked different cell
					this.cellToHighlight = cellHighlight;
					if (cell.hasAcross()) {
						// if moving down already highlighted clue, continue to highlight down clue
						// and not across
						if (cell.hasDown() && cell.downClue.number == clueToHighlight) {
							highlightClue(cell.downClue, DOWN);
							this.cellToHighlight = cellHighlight;
						} else {
							highlightClue(cell.acrossClue, ACROSS);
							this.cellToHighlight = cellHighlight;
						}
					} else if (cell.hasDown()) {
						highlightClue(cell.downClue, DOWN);
						this.cellToHighlight = cellHighlight;
					} else {
						highlightNone();
					}
				}
			} else {// if clicked on black cell, highlight none.
				highlightNone();
			}
			repaint();
		}

		private void highlightNone() {
			this.cellToHighlight = new Point(-1, -1);
			highlightClue(null, NONE);
		}

		private void selectClueInList(Clue clue, int direction) {
			if (clue != null) {
				if (direction == ACROSS) {
					downJList.clearSelection();
					acrossJList.setSelectedValue(clue, true);
				} else if (direction == DOWN) {
					acrossJList.clearSelection();
					downJList.setSelectedValue(clue, true);
				}
			} else {
				acrossJList.clearSelection();
				downJList.clearSelection();
			}

		}

		private void highlightClue(Clue clue, int direction) {
			highlightDirection = direction;
			if (direction == NONE) {
				clueToHighlight = 0;
			} else {
				clueToHighlight = clue.number;
			}
			selectClueInList(clue, direction);
			requestFocus();
		}

		private void onlyHighlightClue(int x, int y, int clueNum, int direction) {
			if (direction == ACROSS) {
				highlightClue(puzzle[x][y].acrossClue, direction);
			} else {
				highlightClue(puzzle[x][y].downClue, direction);
			}
			cellToHighlight = new Point(x, y);
			repaint();
			requestFocus();
		}

		private void setCell(char c) {
			if (connected) {
				outStream(cellToHighlight.x, cellToHighlight.y, c, name);
			}
			setCell(c, true);
		}

		private void setCell(char c, boolean forward) {
			setCell(cellToHighlight.x, cellToHighlight.y, c, name, forward);
		}

		private void setCell(int x, int y, char c, String username) {
			setCell(x, y, c, username, true);
		}

		private void setCell(int x, int y, char c, String username, boolean forward) {
			int dir = 0;
			if (forward) {
				if (highlightDirection == ACROSS)
					dir = RIGHT;
				else
					dir = DOWN;
			} else {
				if (highlightDirection == ACROSS)
					dir = LEFT;
				else
					dir = UP;
			}
			setCell(x, y, c, username, dir);
		}

		private void setCell(int x, int y, char c, String username, int direction) {
			System.out.println("PuzzleGUI.CrosswordGrid.setCell()" + x + " " + y);
			puzzle[x][y].c = Character.toString(c);
			checkClueSolved(puzzle[x][y], username);
			move(direction);
		}

		private void checkClueSolved(Cell cell, String username) {
			if (cell.hasAcross())
				checkClueSolved(cell.acrossClue, ACROSS, username);
			if (cell.hasDown())
				checkClueSolved(cell.downClue, DOWN, username);

		}

		private void checkClueSolved(Clue clue, int direct, String username) {
			boolean solved = true;
			String direction;
			if (direct == ACROSS)
				direction = "across";
			else
				direction = "down";
			if (direct == ACROSS) {
				int y = clue.y;
				for (int x = clue.x; x < clue.x + clue.length(); x++) {
					solved = solved && puzzle[x][y].c.equals(puzzle[x][y].answer);
					if (!solved)
						break;
				}
			} else {
				int x = clue.x;
				for (int y = clue.y; y < clue.y + clue.length(); y++) {
					solved = solved && puzzle[x][y].c.equals(puzzle[x][y].answer);
					if (!solved)
						break;
				}
			}
			if (solved && !clue.isSolved()) {
				clue.setSolved(username);
				if (solvedSupport)
					logArea.append(getTime() + "\n\t" + clue.number + " " + direction + " solved by " + username + "\n");
			} else if (!solved && clue.isSolved()) {
				clue.setUnsolved();
			}
			if (solved && checkPuzzleSolved()) {
				logArea.append(getTime() + "\n\tCrossword \"" + currentCrossword + "\" solved by " + username + "\n");
			}
		}

		protected String getTime() {
			Date now = new Date();
			SimpleDateFormat formatter = new SimpleDateFormat("dd/MM/yyyy HH:mm");
			return formatter.format(now);
		}

		private boolean checkPuzzleSolved() {
			boolean solved = true;
			Iterator<Clue> acrossIterator = currentCrossword.acrossClues.iterator();
			Iterator<Clue> downIterator = currentCrossword.downClues.iterator();
			while (acrossIterator.hasNext() || downIterator.hasNext()) {
				if (acrossIterator.hasNext())
					solved = solved && acrossIterator.next().isSolved();
				if (downIterator.hasNext())
					solved = solved && downIterator.next().isSolved();
				if (!solved)
					return false;
			}
			return true;
		}

		private void move(int direction) {
			int xMove = 0, yMove = 0;
			switch (direction) {
			case UP:
				yMove = -1;
				break;
			case DOWN:
				yMove = 1;
				break;
			case LEFT:
				xMove = -1;
				break;
			case RIGHT:
				xMove = 1;
				break;
			}
			int x = cellToHighlight.x + xMove;
			int y = cellToHighlight.y + yMove;
			highlightCell(x, y);
		}

		public void setPuzzle(Cell[][] puzzle) {
			this.puzzle = puzzle;
			repaint();
		}
	}

	class Cell {
		private String c;
		private String answer;
		private String clueNum; // only if first character
		private Clue acrossClue, downClue;

		public Cell(char answer, Clue acrossClue, Clue downClue) {
			this(' ', answer, acrossClue, downClue);
		}

		public Cell(char c, char answer, Clue acrossClue, Clue downClue) {
			this.c = Character.toString(c);
			this.answer = Character.toString(answer);
			this.acrossClue = acrossClue;
			this.downClue = downClue;
		}

		private boolean hasAcross() {
			return acrossClue != null;
		}

		private boolean hasDown() {
			return downClue != null;
		}

		private boolean isStart() {
			return clueNum != null;
		}
	}
}

class Crossword {

	final ArrayList<Clue> acrossClues, downClues;
	final String title;
	final int size;

	Crossword(String title, int size, ArrayList<Clue> acrossClues, ArrayList<Clue> downClues) {
		this.title = title;
		this.size = size;
		this.acrossClues = acrossClues;
		this.downClues = downClues;
	}

	public void resetCrossword() {
		Iterator<Clue> acrossIterator = acrossClues.iterator();
		Iterator<Clue> downIterator = downClues.iterator();
		while (acrossIterator.hasNext() || downIterator.hasNext()) {
			if (acrossIterator.hasNext())
				acrossIterator.next().setUnsolved();
			if (downIterator.hasNext())
				downIterator.next().setUnsolved();
		}
	}

	@Override
	public String toString() {
		return title + " (" + size + "x" + size + ")";
	}

	// Assume each crossword has a unique name

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((title == null) ? 0 : title.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		Crossword other = (Crossword) obj;
		if (title == null) {
			if (other.title != null)
				return false;
		} else if (!title.equals(other.title))
			return false;
		return true;
	}

}

class Clue {

	final int number, x, y, length;
	final String clue, answer;
	boolean solved;
	String solvedBy;
	Date solvedAt;
	String clueDisplay;

	Clue(int number, int x, int y, String clue, String answer) {
		this.number = number;
		this.x = x;
		this.y = y;
		this.clue = clue;
		this.answer = answer;
		length = answer.replaceAll("(-| )", "").length();
		createClueDisplay();
	}

	private void createClueDisplay() {
		String temp = answer;
		String[] words = answer.split("(-| )");
		clueDisplay = number + ". " + clue + " (";
		int i;
		for (i = 0; i < words.length - 1; i++) {
			clueDisplay += words[i].length();
			temp = temp.replaceFirst(words[i], "");
			if (temp.charAt(0) == '-') {
				clueDisplay += "-";
			} else if (temp.charAt(0) == ' ') {
				clueDisplay += ",";
			}
		}
		clueDisplay += words[i].length();
		clueDisplay += ")";
	}

	void setSolved(String name) {
		setSolved(name, new Date());
	}

	void setSolved(String name, Date date) {
		solved = true;
		solvedBy = name;
		solvedAt = date;
	}

	void setUnsolved() {
		solved = false;
		solvedBy = null;
		solvedAt = null;
	}

	boolean isSolved() {
		return solved;
	}

	int length() {
		return length;
	}

	@Override
	public String toString() {
		return clueDisplay;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((answer == null) ? 0 : answer.hashCode());
		result = prime * result + ((clue == null) ? 0 : clue.hashCode());
		result = prime * result + number;
		result = prime * result + x;
		result = prime * result + y;
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		Clue other = (Clue) obj;
		if (answer == null) {
			if (other.answer != null)
				return false;
		} else if (!answer.equals(other.answer))
			return false;
		if (clue == null) {
			if (other.clue != null)
				return false;
		} else if (!clue.equals(other.clue))
			return false;
		if (number != other.number)
			return false;
		if (x != other.x)
			return false;
		if (y != other.y)
			return false;
		return true;
	}

}

class CrosswordIO {

	// return null if failed
	public static Crossword readPuzzle(File file) {
		System.out.println("reading puzzle");
		return inputPuzzle(file, true);
	}

	// return null if failed
	public static Crossword importPuzzle(File file) {
		return inputPuzzle(file, false);
	}

	private static Crossword inputPuzzle(File file, boolean readState) {
		CrosswordSAXParser parser = new CrosswordSAXParser(file, readState);
		System.out.println("done");
		return parser.getCrossword();
	}

	// save puzzle with state
	public static void writePuzzle(File file, Crossword crossword) {
		ouputPuzzle(file, crossword, true);
	}

	public static void exportPuzzle(File file, Crossword crossword) {
		ouputPuzzle(file, crossword, false);
	}

	private static void ouputPuzzle(File file, Crossword crossword, boolean storeState) {
		OutputStream outputStream = null;
		try {
			outputStream = new FileOutputStream(file);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
		XMLStreamWriter writer = null;

		try {
			writer = XMLOutputFactory.newInstance()
					.createXMLStreamWriter(new OutputStreamWriter(outputStream, "utf-8"));
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		} catch (XMLStreamException e) {
			e.printStackTrace();
		} catch (FactoryConfigurationError e) {
			e.printStackTrace();
		}

		try {
			writer.writeStartDocument();
			writer.writeStartElement("crossword");

			writer.writeAttribute("title", crossword.title);
			writer.writeAttribute("size", Integer.toString(crossword.size));

			writer.writeStartElement("across");
			for (Clue clue : crossword.acrossClues) {
				writeClue(writer, clue, storeState);
			}
			writer.writeEndElement();

			writer.writeStartElement("down");
			for (Clue clue : crossword.downClues) {
				writeClue(writer, clue, storeState);
			}
			writer.writeEndElement();

			writer.writeEndElement();
			writer.writeEndDocument();

			writer.flush();
			writer.close();
		} catch (XMLStreamException e) {
			e.printStackTrace();
		}
	}

	private static void writeClue(XMLStreamWriter writer, Clue clue, boolean storeState) throws XMLStreamException {
		writer.writeStartElement("clueEntry");

		writer.writeAttribute("number", Integer.toString(clue.number));
		writer.writeAttribute("x", Integer.toString(clue.x));
		writer.writeAttribute("y", Integer.toString(clue.y));

		if (storeState) {
			writer.writeAttribute("solved", Boolean.toString(clue.isSolved()));
			if (clue.isSolved()) {
				writer.writeStartElement("solvedBy");
				writer.writeCharacters(clue.solvedBy);
				writer.writeEndElement();

				writer.writeStartElement("solvedAt");

				DateFormat dt = DateFormat.getDateTimeInstance();
				writer.writeCharacters(dt.format(clue.solvedAt));
				writer.writeEndElement();
			}
		}

		writer.writeStartElement("clue");
		writer.writeCharacters(clue.clue);
		writer.writeEndElement();

		writer.writeStartElement("answer");
		writer.writeCharacters(clue.answer);
		writer.writeEndElement();

		writer.writeEndElement();
	}

	public static File getFile(final String[] extensionsAllowed, boolean read) {
		final JFileChooser fc = new JFileChooser();
		fc.setFileFilter(new FileFilter() {

			// Accept all directories and all allowed extensions files.
			public boolean accept(File f) {
				if (f.isDirectory()) {
					return true;
				}

				// get extension
				String fileName = f.getName();
				int i = fileName.lastIndexOf('.');
				String extension = "";
				if (i > 0 && i < fileName.length() - 1) {
					extension = fileName.substring(i + 1).toLowerCase();
				}

				return checkExtension(extension, extensionsAllowed);
			}

			// The description of this filter
			public String getDescription() {
				return Arrays.toString(extensionsAllowed);
			}
		});
		fc.setAcceptAllFileFilterUsed(false);
		int returnVal;
		if (read)
			returnVal = fc.showOpenDialog(null);
		else
			returnVal = fc.showSaveDialog(null);
		if (returnVal == JFileChooser.APPROVE_OPTION) {
			File file = fc.getSelectedFile();
			String fileName = file.getName();
			int i = fileName.lastIndexOf('.');
			String extension = "";
			if (i > 0 && i < fileName.length() - 1) {
				extension = fileName.substring(i + 1).toLowerCase();
			}
			// only accept these extensions
			if (checkExtension(extension, extensionsAllowed)) {
				return file;
			} else {
				Object[] options = { "Try Again", "Cancel" };
				int n = JOptionPane.showOptionDialog(null, "Invalid extension", "Error",
						JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.ERROR_MESSAGE, null, options, options[1]);
				if (n == 0) // Try again
					return getFile(extensionsAllowed, read);
			}
		}
		return null;
	}

	private static boolean checkExtension(String extension, String[] extensionAllowed) {
		if (extension != null) {
			boolean allowed = false;
			for (int i = 0; i < extensionAllowed.length; i++) {
				allowed = allowed || extension.equals(extensionAllowed[i]);
				if (allowed)
					return true;
			}
		}
		return false;
	}
}

/*
 * I used a SAX parser for reading the XML file because they are more efficient than other methods and scale
 * well for big files. For the purpose of this assignment this is was most likely not necessary and could have
 * been done in a much neater way using a DOM parser or another alternative, but I already had the code from a
 * previous project of mine so I decided to use it and adapt it.
 */
class CrosswordSAXParser extends DefaultHandler {

	private Crossword crossword;

	private ArrayList<Clue> acrossClues, downClues;
	private File xmlFile;
	private String tempVal;
	private boolean across, down;

	private Clue tempClue;
	private String tempClueStr, tempAnswerStr, tempNumStr, tempXStr, tempYStr, tempSolvedByStr, tempSolvedAtStr;
	private boolean tempSolved;
	private boolean readState;

	public CrosswordSAXParser(File xmlFile, boolean readState) {
		this.xmlFile = xmlFile;
		acrossClues = new ArrayList<Clue>();
		downClues = new ArrayList<Clue>();
		this.readState = readState;
		parseDocument();
	}

	private void parseDocument() {

		// get a factory
		SAXParserFactory spf = SAXParserFactory.newInstance();
		try {
			// get a new instance of parser
			SAXParser sp = spf.newSAXParser();

			// parse the file
			sp.parse(xmlFile, this);
		} catch (Exception e) { // just set to null if exception thrown
			crossword = null;
			e.printStackTrace();
		}
	}

	public Crossword getCrossword() {
		return crossword;
	}

	// Event Handlers
	public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
		// reset
		tempVal = "";
		if (qName.equalsIgnoreCase("crossword")) {
			String title = attributes.getValue("title");
			int size = Integer.parseInt(attributes.getValue("size"));
			crossword = new Crossword(title, size, acrossClues, downClues);
		} else if (qName.equalsIgnoreCase("across")) {
			across = true;
		} else if (qName.equalsIgnoreCase("down")) {
			down = true;
		} else if (qName.equalsIgnoreCase("clueEntry")) {
			System.out.println("found entry, state:" + readState);
			tempNumStr = attributes.getValue("number");
			tempXStr = attributes.getValue("x");
			tempYStr = attributes.getValue("y");
			if (readState) {
				System.out.println("reading state");
				if (attributes.getValue("solved") != null)
					tempSolved = Boolean.parseBoolean(attributes.getValue("solved"));
				System.out.println("state: " + tempSolved);
			}
		}

	}

	public void characters(char[] ch, int start, int length) throws SAXException {
		tempVal = new String(ch, start, length);
	}

	public void endElement(String uri, String localName, String qName) throws SAXException {

		if (qName.equalsIgnoreCase("clue")) {
			tempClueStr = tempVal;
		} else if (qName.equalsIgnoreCase("across")) {
			across = false;
		} else if (qName.equalsIgnoreCase("down")) {
			down = false;
		} else if (qName.equalsIgnoreCase("answer")) {
			tempAnswerStr = tempVal;
		} else if (qName.equalsIgnoreCase("solvedBy")) {
			tempSolvedByStr = tempVal;
		} else if (qName.equalsIgnoreCase("solvedAt")) {
			tempSolvedAtStr = tempVal;
		} else if (qName.equalsIgnoreCase("clueEntry")) {

			int num = Integer.parseInt(tempNumStr);
			int x = Integer.parseInt(tempXStr);
			int y = Integer.parseInt(tempYStr);

			tempClue = new Clue(num, x, y, tempClueStr, tempAnswerStr);
			if (tempSolved) {
				DateFormat dt = DateFormat.getDateTimeInstance();
				try {
					tempClue.setSolved(tempSolvedByStr, dt.parse(tempSolvedAtStr));
				} catch (ParseException e) {
					e.printStackTrace();
				}
			}
			tempSolved = false;

			if (across)
				acrossClues.add(tempClue);
			else if (down)
				downClues.add(tempClue);

		}
	}
}