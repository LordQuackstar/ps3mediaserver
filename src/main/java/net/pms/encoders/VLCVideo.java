/*
 * PS3 Media Server, for streaming any medias to your PS3.
 * Copyright (C) 2008  A.Brochard
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; version 2
 * of the License only.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package net.pms.encoders;

import com.jgoodies.forms.builder.DefaultFormBuilder;
import com.jgoodies.forms.layout.CellConstraints;
import com.jgoodies.forms.layout.FormLayout;
import com.sun.jna.Platform;

import java.awt.ComponentOrientation;
import java.awt.Font;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.util.*;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import net.pms.Messages;
import net.pms.PMS;
import net.pms.configuration.PmsConfiguration;
import net.pms.configuration.RendererConfiguration;
import net.pms.dlna.DLNAMediaInfo;
import net.pms.dlna.DLNAResource;
import net.pms.formats.Format;
import net.pms.io.OutputParams;
import net.pms.io.PipeProcess;
import net.pms.io.ProcessWrapper;
import net.pms.io.ProcessWrapperImpl;
import net.pms.network.HTTPResource;
import net.pms.newgui.LooksFrame;
import net.pms.util.FormLayoutUtil;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Use VLC as a backend transcoder. Note that 0.x and 1.x versions are
 * unsupported (and probably will crash). Only the latest version will be
 * supported
 *
 * @author Leon Blakey <lord.quackstar@gmail.com>
 */
public class VLCVideo extends Player {
	private static final Logger LOGGER = LoggerFactory.getLogger(VLCVideo.class);
	protected final PmsConfiguration pmsconfig;
	public static final String ID = "vlctranscoder";
	protected JCheckBox hardwareAccel;
	protected JTextField audioPri;
	protected JTextField subtitlePri;
	protected JTextField scale;
	protected JCheckBox experimentalCodecs;
	protected JCheckBox audioSyncEnabled;
	protected JTextField sampleRate;
	protected JCheckBox sampleRateOverride;
	protected JTextField extraParams;

	public VLCVideo(PmsConfiguration pmsconfig) {
		this.pmsconfig = pmsconfig;
	}

	@Override
	public int purpose() {
		return VIDEO_SIMPLEFILE_PLAYER;
	}

	@Override
	public String id() {
		return ID;
	}

	@Override
	public boolean isTimeSeekable() {
		return true;
	}

	@Override
	public boolean avisynth() {
		return false;
	}

	@Override
	public String[] args() {
		return new String[]{};
	}

	@Override
	public String name() {
		return "VLC Transcoder";
	}

	@Override
	public int type() {
		return Format.VIDEO;
	}

	@Override
	public String mimeType() {
		// I think?
		return HTTPResource.VIDEO_TRANSCODE;
	}

	@Override
	public String executable() {
		return pmsconfig.getVlcPath();
	}

	@Override
	public boolean isCompatible(DLNAResource resource) {
		// VLC is a general transcoder that should support every format
		// Until problem occurs, assume compatible
		return true;
	}

	/**
	 * Pick codecs for VLC based on formats the client supports;
	 *
	 * @param formats
	 * @return
	 */
	protected CodecConfig genConfig(RendererConfiguration renderer) {
		CodecConfig config = new CodecConfig();
		if (renderer.isTranscodeToWMV()) {
			// Assume WMV = XBox = all media renderers with this flag
			LOGGER.debug("Using XBox WMV codecs");
			config.videoCodec = "wmv2";
			config.audioCodec = "wma";
			config.container = "asf";
		} else if (renderer.isTranscodeToMPEGTSAC3()) {
			// Default codecs for DLNA standard
			LOGGER.debug("Using DLNA standard codecs with ts container");
			config.videoCodec = "mp2v";
			config.audioCodec = "mp2a"; // NOTE: a52 sometimes causes audio to stop after ~5 mins
			config.container = "ts";
		} else {
			// Default codecs for DLNA standard
			LOGGER.debug("Using DLNA standard codecs with ps (default) container");
			config.videoCodec = "mp2v";
			config.audioCodec = "mp2a"; // NOTE: a52 sometimes causes audio to stop after ~5 mins
			config.container = "ps";
		}
		LOGGER.trace("Using " + config.videoCodec + ", " + config.audioCodec + ", " + config.container);

		// Audio sample rate handling
		if (sampleRateOverride.isSelected()) {
			config.sampleRate = Integer.valueOf(sampleRate.getText());
		}

		// This has caused garbled audio, so only enable when told to
		if (audioSyncEnabled.isSelected()) {
			config.extraTrans.put("audio-sync", "");
		}
		return config;
	}

	protected static class CodecConfig {
		String videoCodec;
		String audioCodec;
		String container;
		String extraParams;
		HashMap<String, Object> extraTrans = new HashMap<String, Object>();
		int sampleRate;
	}

	protected Map<String, Object> getEncodingArgs(CodecConfig config) {
		// See: http://www.videolan.org/doc/streaming-howto/en/ch03.html
		// See: http://wiki.videolan.org/Codec
		Map<String, Object> args = new HashMap<String, Object>();

		// Codecs to use
		args.put("vcodec", config.videoCodec);
		args.put("acodec", config.audioCodec);

		// Bitrate in kbit/s (TODO: Use global option?)
		args.put("vb", "4096");
		args.put("ab", "128");

		// Video scaling
		args.put("scale", scale.getText());

		// Audio Channels
		args.put("channels", 2);

		// Static sample rate
		args.put("samplerate", config.sampleRate);

		// Recommended on VLC DVD encoding page
		args.put("keyint", 16);

		// Recommended on VLC DVD encoding page
		args.put("strict-rc", "");

		// Stream subtitles to client
		// args.add("scodec=dvbs");
		// args.add("senc=dvbsub");

		// Hardcode subtitles into video
		args.put("soverlay", "");

		// Add extra args
		args.putAll(config.extraTrans);

		return args;
	}

	@Override
	public ProcessWrapper launchTranscode(String fileName, DLNAResource dlna, DLNAMediaInfo media, OutputParams params) throws IOException {
		boolean isWindows = Platform.isWindows();

		// Make sure we can play this
		CodecConfig config = genConfig(params.mediaRenderer);

		PipeProcess tsPipe = new PipeProcess("VLC" + System.currentTimeMillis() + "." + config.container);
		ProcessWrapper pipe_process = tsPipe.getPipeProcess();

		LOGGER.trace("filename: " + fileName);
		LOGGER.trace("dlna: " + dlna);
		LOGGER.trace("media: " + media);
		LOGGER.trace("outputparams: " + params);

		// XXX it can take a long time for Windows to create a named pipe
		// (and mkfifo can be slow if /tmp isn't memory-mapped), so start this as early as possible
		pipe_process.runInNewThread();
		tsPipe.deleteLater();

		params.input_pipes[0] = tsPipe;
		params.minBufferSize = params.minFileSize;
		params.secondread_minsize = 100000;

		List<String> cmdList = new ArrayList<String>();
		cmdList.add(executable());
		cmdList.add("-I");
		cmdList.add("dummy");

		// Hardware acceleration seems to be more stable now, so its enabled
		if (hardwareAccel.isSelected()) {
			cmdList.add("--ffmpeg-hw");
		}

		// Useful for the more esoteric codecs people use
		if (experimentalCodecs.isSelected()) {
			cmdList.add("--sout-ffmpeg-strict=-2");
		}

		// Stop the DOS box from appearing on windows
		if (isWindows) {
			cmdList.add("--dummy-quiet");
		}

		// File needs to be given before sout, otherwise vlc complains
		cmdList.add(fileName);

		// Huge fake track id that shouldn't conflict with any real subtitle or audio id. Hopefully.
		String disableSuffix = "track=214748361";

		// Handle audio language
		if (params.aid != null) { // User specified language at the client, acknowledge it
			if (params.aid.getLang() == null || params.aid.getLang().equals("und")) { // VLC doesn't understand und, but does understand a non existant track
				cmdList.add("--audio-" + disableSuffix);
			} else { // Load by ID (better)
				cmdList.add("--audio-track=" + params.aid.getId());
			}
		} else { // Not specified, use language from GUI
			cmdList.add("--audio-language=" + audioPri.getText());
		}

		// Handle subtitle language
		if (params.sid != null) { // User specified language at the client, acknowledge it
			if (params.sid.getLang() == null || params.sid.getLang().equals("und")) { // VLC doesn't understand und, but does understand a non existant track
				cmdList.add("--sub-" + disableSuffix);
			} else { // Load by ID (better)
				cmdList.add("--sub-track=" + params.sid.getId());
			}
		} else if (!pmsconfig.isMencoderDisableSubs()){ // Not specified, use language from GUI if enabled
			cmdList.add("--sub-language=" + subtitlePri.getText());
		} else {
			cmdList.add("--sub-" + disableSuffix);
		}

		// Skip forward if nessesary
		if (params.timeseek != 0) {
			cmdList.add("--start-time");
			cmdList.add(String.valueOf(params.timeseek));
		}

		// Generate encoding args
		StringBuilder encodingArgsBuilder = new StringBuilder();
		for (Map.Entry<String, Object> curEntry : getEncodingArgs(config).entrySet()) {
			encodingArgsBuilder.append(curEntry.getKey()).append("=").append(curEntry.getValue()).append(",");
		}

		// Add our transcode options
		String transcodeSpec = String.format(
				"#transcode{%s}:std{access=file,mux=%s,dst=\"%s%s\"}",
				encodingArgsBuilder.toString(),
				config.container,
				(isWindows ? "\\\\" : ""),
				tsPipe.getInputPipe());
		cmdList.add("--sout");
		cmdList.add(transcodeSpec);

		// Force VLC to die when finished
		cmdList.add("vlc:// quit");

		// Add any extra parameters
		if (!extraParams.getText().trim().isEmpty()) { // Add each part as a new item
			cmdList.addAll(Arrays.asList(StringUtils.split(extraParams.getText().trim(), " ")));
		}

		// Pass to process wrapper
		String[] cmdArray = new String[cmdList.size()];
		cmdList.toArray(cmdArray);
		cmdArray = finalizeTranscoderArgs(this, fileName, dlna, media, params, cmdArray);
		LOGGER.trace("Finalized args: " + StringUtils.join(cmdArray, " "));
		ProcessWrapperImpl pw = new ProcessWrapperImpl(cmdArray, params);
		pw.attachProcess(pipe_process);

		// TODO: Why is this here?
		try {
			Thread.sleep(150);
		} catch (InterruptedException e) {
		}

		pw.runInNewThread();
		return pw;
	}

	@Override
	public JComponent config() {
		// Apply the orientation for the locale
		Locale locale = new Locale(pmsconfig.getLanguage());
		ComponentOrientation orientation = ComponentOrientation.getOrientation(locale);
		String colSpec = FormLayoutUtil.getColSpec("p, 3dlu, pref:grow, 7dlu, p, 3dlu, pref:grow", orientation);
		FormLayout layout = new FormLayout(colSpec, "p, 2dlu, p, 2dlu, p, 2dlu, p, 2dlu, p, 2dlu, fill:30dlu, 2dlu, fill:40dlu, 9dlu, p, 2dlu, p, 2dlu, p");
		layout.setColumnGroups(new int[][]{{1, 5}, {3, 7}});
		DefaultFormBuilder mainPanel = new DefaultFormBuilder(layout);
		CellConstraints cc = new CellConstraints();

		JComponent cmp = mainPanel.addSeparator(Messages.getString("VlcTrans.1"), FormLayoutUtil.flip(cc.xyw(1, 1, 7), colSpec, orientation));
		cmp = (JComponent) cmp.getComponent(0);
		cmp.setFont(cmp.getFont().deriveFont(Font.BOLD));
		
		hardwareAccel = new JCheckBox(Messages.getString("VlcTrans.2"), pmsconfig.isVlcUseHardwareAccel());
		hardwareAccel.setContentAreaFilled(false);
		hardwareAccel.addItemListener(new ItemListener() {
			@Override
			public void itemStateChanged(ItemEvent e) {
				pmsconfig.setVlcUseHardwareAccel(e.getStateChange() == ItemEvent.SELECTED);
			}
		});
		mainPanel.add(hardwareAccel, FormLayoutUtil.flip(cc.xy(1, 3), colSpec, orientation));
		
		experimentalCodecs = new JCheckBox(Messages.getString("VlcTrans.3"), pmsconfig.isVlcExperimentalCodecs());
		experimentalCodecs.setContentAreaFilled(false);
		experimentalCodecs.addItemListener(new ItemListener() {
			@Override
			public void itemStateChanged(ItemEvent e) {
				pmsconfig.setVlcExperimentalCodecs(e.getStateChange() == ItemEvent.SELECTED);
			}
		});
		mainPanel.add(experimentalCodecs, FormLayoutUtil.flip(cc.xy(5, 3), colSpec, orientation));
		
		audioSyncEnabled = new JCheckBox(Messages.getString("VlcTrans.4"), pmsconfig.isVlcAudioSyncEnabled());
		audioSyncEnabled.setContentAreaFilled(false);
		audioSyncEnabled.addItemListener(new ItemListener() {
			@Override
			public void itemStateChanged(ItemEvent e) {
				pmsconfig.setVlcAudioSyncEnabled(e.getStateChange() == ItemEvent.SELECTED);
			}
		});
		mainPanel.add(audioSyncEnabled, FormLayoutUtil.flip(cc.xy(1, 5), colSpec, orientation));

		mainPanel.addLabel(Messages.getString("VlcTrans.6"), FormLayoutUtil.flip(cc.xy(1, 7), colSpec, orientation));
		audioPri = new JTextField(pmsconfig.getVlcAudioPri());
		audioPri.addKeyListener(new KeyAdapter() {
			@Override
			public void keyReleased(KeyEvent e) {
				pmsconfig.setVlcAudioPri(audioPri.getText());
			}
		});
		mainPanel.add(audioPri, FormLayoutUtil.flip(cc.xy(3, 7), colSpec, orientation));
		
		mainPanel.addLabel(Messages.getString("VlcTrans.8"), FormLayoutUtil.flip(cc.xy(5, 7), colSpec, orientation));
		subtitlePri = new JTextField(pmsconfig.getVlcSubtitlePri());
		subtitlePri.setEnabled(!pmsconfig.isDisableSubtitles());
		subtitlePri.addKeyListener(new KeyAdapter() {
			@Override
			public void keyReleased(KeyEvent e) {
				pmsconfig.setVlcSubtitlePri(subtitlePri.getText());
			}
		});
		mainPanel.add(subtitlePri, FormLayoutUtil.flip(cc.xy(7, 7), colSpec, orientation));
		
		JCheckBox disableSubs = ((LooksFrame) PMS.get().getFrame()).getTr().getDisableSubs();
		disableSubs.addItemListener(new ItemListener() {
			public void itemStateChanged(ItemEvent e) {
				subtitlePri.setEnabled(pmsconfig.isDisableSubtitles());
			}		
		});

		// Developer stuff. Theoretically is temporary 
		cmp = mainPanel.addSeparator(Messages.getString("VlcTrans.10"), FormLayoutUtil.flip(cc.xyw(1, 9, 7), colSpec, orientation));
		cmp = (JComponent) cmp.getComponent(0);
		cmp.setFont(cmp.getFont().deriveFont(Font.BOLD));

		// Add scale as a subpanel because it has an awkward layout
		String scaleColSpec = FormLayoutUtil.getColSpec("p, 3dlu, p, 3dlu, p", orientation);
		FormLayout scaleLayout = new FormLayout(scaleColSpec , "p");
		DefaultFormBuilder scalePanel = new DefaultFormBuilder(scaleLayout);
		double startingScale = Double.valueOf(pmsconfig.getVlcScale());
		scalePanel.addLabel(Messages.getString("VlcTrans.11"), FormLayoutUtil.flip(cc.xy(1, 1), scaleColSpec, orientation));
		scalePanel.add(scale = new JTextField(String.valueOf(startingScale)), FormLayoutUtil.flip(cc.xy(3, 1), scaleColSpec, orientation));
		final JSlider scaleSlider = new JSlider(JSlider.HORIZONTAL, 0, 10, (int) (startingScale * 10));
		scalePanel.add(scaleSlider, FormLayoutUtil.flip(cc.xy(5, 1), scaleColSpec, orientation));
		scaleSlider.addChangeListener(new ChangeListener() {
			@Override
			public void stateChanged(ChangeEvent ce) {
				String value = String.valueOf((double) scaleSlider.getValue() / 10);
				scale.setText(value);
				pmsconfig.setVlcScale(value);
			}
		});
		scale.addKeyListener(new KeyAdapter() {
			@Override
			public void keyReleased(KeyEvent e) {
				String typed = scale.getText();
				if (!typed.matches("\\d\\.\\d")) {
					return;
				}
				double value = Double.parseDouble(typed);
				scaleSlider.setValue((int) (value * 10));
				pmsconfig.setVlcScale(String.valueOf(value));
			}
		});
		mainPanel.add(scalePanel.getPanel(), FormLayoutUtil.flip(cc.xyw(1, 11, 7), colSpec, orientation));

		// Audio sample rate
		String sampleRateColSpec = FormLayoutUtil.getColSpec("p, 3dlu, p, 3dlu, right:pref, 3dlu, left:pref", orientation);
		FormLayout sampleRateLayout = new FormLayout(sampleRateColSpec, "p, 2dlu, p");
		DefaultFormBuilder sampleRatePanel = new DefaultFormBuilder(sampleRateLayout);
		
		sampleRatePanel.addLabel(Messages.getString("VlcTrans.18"), FormLayoutUtil.flip(cc.xy(1, 1), sampleRateColSpec, orientation));
		sampleRateOverride = new JCheckBox(Messages.getString("VlcTrans.17"), pmsconfig.getVlcSampleRateOverride());
		sampleRateOverride.addItemListener(new ItemListener() {
			@Override
			public void itemStateChanged(ItemEvent e) {
				boolean checked = e.getStateChange() == ItemEvent.SELECTED;
				pmsconfig.setVlcSampleRateOverride(checked);
				sampleRate.setEnabled(checked);
			}
		});
		sampleRatePanel.add(sampleRateOverride, FormLayoutUtil.flip(cc.xy(1, 3), sampleRateColSpec, orientation));
		
		sampleRatePanel.addLabel(Messages.getString("VlcTrans.19"), FormLayoutUtil.flip(cc.xy(5, 3), sampleRateColSpec, orientation));
		sampleRate = new JTextField(pmsconfig.getVlcSampleRate(), 8);
		sampleRate.setEnabled(pmsconfig.getVlcSampleRateOverride());
		sampleRate.addKeyListener(new KeyAdapter() {
			@Override
			public void keyReleased(KeyEvent e) {
				pmsconfig.setVlcSampleRate(sampleRate.getText());
			}
		});
		sampleRatePanel.add(sampleRate, FormLayoutUtil.flip(cc.xy(7, 3), sampleRateColSpec, orientation));
		
		mainPanel.add(sampleRatePanel.getPanel(), FormLayoutUtil.flip(cc.xyw(1, 13, 7), colSpec, orientation));

		// Extra options
		mainPanel.addLabel(Messages.getString("VlcTrans.20"), FormLayoutUtil.flip(cc.xy(1, 15), colSpec, orientation));
		extraParams = new JTextField("", 30);
		mainPanel.add(extraParams, FormLayoutUtil.flip(cc.xyw(3, 15, 5), colSpec, orientation));
		
		JPanel panel = mainPanel.getPanel();
		
		// Apply the orientation to the panel and all components in it
		panel.applyComponentOrientation(orientation);

		return panel;
	}
}
