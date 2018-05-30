package forge.screens.home.quest;

import forge.assets.FSkinProp;
import forge.card.CardEdition;
import forge.game.GameFormat;
import forge.gui.SOverlayUtils;
import forge.model.FModel;
import forge.toolbox.*;
import forge.util.TextUtil;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.*;

public class DialogChooseFormats {

	private List<GameFormat> selectedFormats = new ArrayList<>() ;
	private boolean wantReprints = true;
	private Runnable okCallback;

	private final List<FCheckBox> choices = new ArrayList<>();
	private final FCheckBox cbWantReprints = new FCheckBox("Allow compatible reprints from other sets");

	public DialogChooseFormats(){
		this(null);
	}

	public DialogChooseFormats(Set<GameFormat> preselectedFormats) {

		List<FCheckBox> sanctioned = new ArrayList<>();
		List<FCheckBox> casual = new ArrayList<>();
		List<FCheckBox> historic = new ArrayList<>();

		for (GameFormat format : FModel.getFormats().getOrderedList()){
			FCheckBox box = new FCheckBox(format.getName());
			box.setName(format.getName());
			switch (format.getFormatType()){
				case Sanctioned:
					sanctioned.add(box);
					break;
				case Historic:
					historic.add(box);
					break;
				case Custom:
				case Casual:
				case Digital:
				default:
					casual.add(box);
					break;

			}
			box.setSelected(null != preselectedFormats && preselectedFormats.contains(format));
		}

		FPanel panel = new FPanel(new MigLayout("insets 0, gap 0, center, wrap 3"));
		panel.setOpaque(false);
		panel.setBackgroundTexture(FSkin.getIcon(FSkinProp.BG_TEXTURE));

		panel.add(new FLabel.Builder().text("Choose formats").fontSize(18).build(), "center, span, wrap, gaptop 10");

		String constraints = "aligny top";
		panel.add(makeCheckBoxList(sanctioned, "Sanctioned", true), constraints);
		panel.add(makeCheckBoxList(casual, "Other", false), constraints);
		panel.add(makeCheckBoxList(historic, "Historic", false), constraints);

		final JPanel overlay = FOverlay.SINGLETON_INSTANCE.getPanel();
		overlay.setLayout(new MigLayout("insets 0, gap 0, wrap, ax center, ay center"));

		final Runnable cleanup = new Runnable() {
			@Override
			public void run() {
				SOverlayUtils.hideOverlay();
			}
		};

		FButton btnOk = new FButton("OK");
		btnOk.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent arg0) {
				cleanup.run();
				handleOk();
			}
		});

		FButton btnCancel = new FButton("Cancel");
		btnCancel.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				cleanup.run();
			}
		});

		JPanel southPanel = new JPanel(new MigLayout("insets 10, gap 20, ax center"));
		southPanel.setOpaque(false);
		southPanel.add(cbWantReprints, "center, span, wrap");
		southPanel.add(btnOk, "center, w 40%, h 20!");
		southPanel.add(btnCancel, "center, w 40%, h 20!");

		panel.add(southPanel, "dock south, gapBottom 10");

		overlay.add(panel);
		panel.getRootPane().setDefaultButton(btnOk);
		SOverlayUtils.showOverlay();

	}

	public void setOkCallback(Runnable onOk) {
		okCallback = onOk;
	}

	public List<GameFormat> getSelectedFormats() {
		return selectedFormats;
	}

	public boolean getWantReprints() {
		return wantReprints;
	}

	private JPanel makeCheckBoxList(List<FCheckBox> formats, String title, boolean focused) {

		choices.addAll(formats);
		final FCheckBoxList<FCheckBox> cbl = new FCheckBoxList<>(false);
		cbl.setListData(formats.toArray(new FCheckBox[formats.size()]));
		cbl.setVisibleRowCount(Math.min(20, formats.size()));

		if (focused) {
			SwingUtilities.invokeLater(new Runnable() {
				@Override
				public void run() {
					cbl.requestFocusInWindow();
				}
			});
		}

		JPanel pnl = new JPanel(new MigLayout("center, wrap"));
		pnl.setOpaque(false);
		pnl.add(new FLabel.Builder().text(title).build());
		pnl.add(new FScrollPane(cbl, true));
		return pnl;

	}

	private void handleOk() {

		for (FCheckBox box : choices) {
			if (box.isSelected()) {
				selectedFormats.add(FModel.getFormats().getFormat(box.getName()));
			}
			wantReprints = cbWantReprints.isSelected();
		}

		if (null != okCallback) {
			okCallback.run();
		}

	}

}
