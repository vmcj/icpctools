package org.icpc.tools.presentation.contest.internal.tile;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.lang.ref.SoftReference;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import org.icpc.tools.contest.Trace;
import org.icpc.tools.contest.model.ContestUtil;
import org.icpc.tools.contest.model.IContest;
import org.icpc.tools.contest.model.IContest.ScoreboardType;
import org.icpc.tools.contest.model.IOrganization;
import org.icpc.tools.contest.model.IProblem;
import org.icpc.tools.contest.model.IProblemSummary;
import org.icpc.tools.contest.model.IResult;
import org.icpc.tools.contest.model.IStanding;
import org.icpc.tools.contest.model.ITeam;
import org.icpc.tools.contest.model.Status;
import org.icpc.tools.contest.model.internal.Contest;
import org.icpc.tools.contest.model.internal.Recent;
import org.icpc.tools.presentation.contest.internal.ICPCColors;
import org.icpc.tools.presentation.contest.internal.ICPCFont;
import org.icpc.tools.presentation.contest.internal.TextHelper;
import org.icpc.tools.presentation.core.RenderPerfTimer;
import org.icpc.tools.presentation.core.internal.PresentationWindowImpl;

public class TeamTileHelper {
	private static final int IN_TILE_GAP = 3;
	protected static final Color TILE_BG = new Color(50, 50, 50);
	protected static final Color TILE_BG_LIGHT = new Color(240, 240, 240);
	private static final Color PROBLEM_BG = new Color(90, 90, 90);
	private static final Color PROBLEM_BG_LIGHT = new Color(160, 160, 160);

	private static double PROBLEM_TIME_SQUEEZE = 3;

	private static boolean DEBUG_APPROXIMATE_RENDERING = false;

	private Font rankFont;
	private Font teamFont;
	private Font statusFont;
	private Font problemFont;
	private Font penaltyFont;

	private Dimension tileDim = null;
	private IContest contest;
	private boolean lightMode;

	private boolean approximateRendering;
	private boolean nameRenderingGlitchAvoidance;

	private Map<String, BufferedImage> nameImages = Collections.synchronizedMap(new HashMap<>());
	private Map<String, SoftReference<BufferedImage>> resultImages = Collections.synchronizedMap(new HashMap<>());
	private Map<String, BufferedImage> problemImages = Collections.synchronizedMap(new HashMap<>());
	private Map<String, BufferedImage> logoImages = Collections.synchronizedMap(new HashMap<>());

	public TeamTileHelper(Dimension tileDim, IContest contest) {
		this.tileDim = tileDim;
		this.contest = contest;

		setup();
	}

	public void joinCaches(TeamTileHelper from) {
		nameImages = from.nameImages;
		resultImages = from.resultImages;
		problemImages = from.problemImages;
		logoImages = from.logoImages;
		nameRenderingGlitchAvoidance = true;
	}

	public void clearCaches() {
		nameImages.clear();
		resultImages.clear();
		problemImages.clear();
		logoImages.clear();
	}

	protected void setSize(Dimension d) {
		this.tileDim = d;
	}

	protected Dimension getSize() {
		return new Dimension(tileDim.width, tileDim.height);
	}

	protected void setLightMode(boolean lightMode) {
		this.lightMode = lightMode;
	}

	public void setApproximateRendering(boolean approximateRendering) {
		this.approximateRendering = approximateRendering;
	}

	protected void setup() {
		final float dpi = 96;

		float size = tileDim.height * 36f * 0.95f / dpi;
		teamFont = ICPCFont.deriveFont(Font.BOLD, size * 1.4f);
		rankFont = ICPCFont.deriveFont(Font.BOLD, size * 1.4f);
		statusFont = ICPCFont.deriveFont(Font.PLAIN, size * 0.7f);
		problemFont = ICPCFont.deriveFont(Font.PLAIN, size * 0.45f);
		penaltyFont = ICPCFont.deriveFont(Font.BOLD, size * 0.85f);
	}

	public void paintTile(Graphics2D g, int x, int y, ITeam team, int timeMs) {
		paintTile(g, x, y, 1.0, team, timeMs, false);
	}

	public void paintTile(Graphics2D g, int x, int y, double scale, ITeam team, int timeMs, boolean preRendering) {
		Graphics2D gg = (Graphics2D) g.create();
		gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		gg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		if (scale != 1.0) {
			gg.translate(x + tileDim.width / 2, y + tileDim.height / 2);
			gg.scale(scale, scale);
			gg.translate(-tileDim.width / 2, -tileDim.height / 2);
		} else
			gg.translate(x, y);

		// paint recent change color
		Recent recent = ((Contest) contest).getRecent(team);
		if (recent != null) {
			long age = timeMs - recent.time;
			if (age > 0 && age < 30000) {
				Color c = ICPCColors.getStatusColor(recent.status, age);
				if (c != null) {
					gg.setColor(c);
					int arc = tileDim.width / 40;
					gg.fillRoundRect(0, 0, tileDim.width, tileDim.height, arc, arc);
				}
			}
		}

		// draw image & name
		gg.setFont(rankFont);
		FontMetrics fm = gg.getFontMetrics();
		int ww = fm.stringWidth("199");

		IOrganization org = contest.getOrganizationById(team.getOrganizationId());
		if (org != null) {
			RenderPerfTimer.Counter logoMeasure = RenderPerfTimer.measure(RenderPerfTimer.Category.LOGO);
			logoMeasure.startMeasure();
			int logoWidth = tileDim.height - 10, logoHeight = logoWidth;
			String logoHash = team.getOrganizationId() + "@" + logoWidth + "x" + logoHeight;
			BufferedImage logoImg = logoImages.get(logoHash);
			if (logoImg == null) {
				Trace.trace(Trace.INFO, "logo cache miss " + logoHash);
				logoImg = org.getLogoImage(logoWidth, logoHeight, true, true);
				logoImages.put(logoHash, logoImg);
				logoImg = cacheMiss(logoImg);
			}
			if (logoImg != null) {
				gg.drawImage(logoImg, ww + (tileDim.height - logoImg.getWidth()) / 2,
						(tileDim.height - logoImg.getHeight()) / 2, null);
			}
			logoMeasure.stopMeasure();
		}

		paintTileForeground(gg, team, timeMs, preRendering);

		gg.dispose();
	}

	private void paintName(Graphics2D g, ITeam team, int ww, int maxwid) {
		BufferedImage img = nameImages.get(team.getId());
		if (img == null) {
			g.setFont(teamFont);
			TextHelper text = new TextHelper(g, team.getActualDisplayName());

			img = new BufferedImage(text.getWidth() + 2, text.getHeight() + 4, BufferedImage.TYPE_4BYTE_ABGR);
			Graphics2D gg = (Graphics2D) img.getGraphics();
			gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			gg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			gg.setFont(teamFont);
			gg.setColor(lightMode ? Color.BLACK : Color.WHITE);
			text.setGraphics(gg);
			text.draw(1, 2);
			gg.dispose();

			if (!nameRenderingGlitchAvoidance) {
				// TODO: for some reason, text renders ever so slightly different when pre-rendering
				// it should render identically, so the natural size cached version could be updated
				nameImages.put(team.getId(), img);
			}
			img = cacheMiss(img);
		}

		int naturalWidth = img.getWidth() - 2;
		if (naturalWidth <= maxwid) {
			g.drawImage(img, ww + tileDim.height + IN_TILE_GAP - 1, tileDim.height * 1 / 10 - 1, null);
			return;
		}

		int hashWidth = maxwid;
		if (approximateRendering) {
			final int APPROX_WIDTH_STEP = 50;
			// pick approximate width higher or equal to maxwid
			int approxmaxwid = (maxwid + APPROX_WIDTH_STEP - 1) / APPROX_WIDTH_STEP * APPROX_WIDTH_STEP;
			// the approximate size can get bigger than the actual natural text length.
			// in that case, adjust it down to the full natural width.
			approxmaxwid = Math.min(approxmaxwid, naturalWidth);
			hashWidth = approxmaxwid;
		}
		String hash = team.getId() + hashWidth;
		// if hashWidth matches the natural width, reuse the
		// already rendered full width image, but just draw it scaled down.
		if (hashWidth < naturalWidth) {
			img = nameImages.get(hash);
		}
		if (img == null) {
			if (approximateRendering) {
				Trace.trace(Trace.INFO, "" + hashWidth + " approximating " + maxwid + " vs natural width " + naturalWidth
						+ " for " + team.getActualDisplayName());
			}
			g.setFont(teamFont);
			TextHelper text = new TextHelper(g, team.getActualDisplayName());

			img = new BufferedImage(hashWidth + 2, text.getHeight() + 4, BufferedImage.TYPE_4BYTE_ABGR);
			Graphics2D gg = (Graphics2D) img.getGraphics();
			gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			gg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			gg.setFont(teamFont);
			gg.setColor(lightMode ? Color.BLACK : Color.WHITE);
			text.setGraphics(gg);
			text.drawFit(1, 2, hashWidth);
			if (approximateRendering && DEBUG_APPROXIMATE_RENDERING) {
				gg.setColor(Color.YELLOW);
				gg.drawString("*", 7, gg.getFontMetrics().getAscent());
			}
			gg.dispose();

			nameImages.put(hash, img);
			img = cacheMiss(img);
		}
		g.drawImage(img, ww + tileDim.height + IN_TILE_GAP - 1, tileDim.height * 1 / 10 - 1, maxwid + 2, img.getHeight(),
				null);
	}

	private void paintTileForeground(Graphics2D g, ITeam team, int timeMs, boolean preRendering) {
		RenderPerfTimer.Counter rankAndScoreMeasure = RenderPerfTimer.measure(RenderPerfTimer.Category.RANK_AND_SCORE);
		RenderPerfTimer.Counter nameMeasure = RenderPerfTimer.measure(RenderPerfTimer.Category.NAME);
		RenderPerfTimer.Counter problemMeasure = RenderPerfTimer.measure(RenderPerfTimer.Category.PROBLEM);
		RenderPerfTimer.Counter inactiveProblemMeasure = RenderPerfTimer
				.measure(RenderPerfTimer.Category.INACTIVE_PROBLEM);
		RenderPerfTimer.Counter inactive2ProblemMeasure = RenderPerfTimer
				.measure(RenderPerfTimer.Category.INACTIVE_PROBLEM2);
		RenderPerfTimer.Counter activeProblemMeasure = RenderPerfTimer.measure(RenderPerfTimer.Category.ACTIVE_PROBLEM);
		RenderPerfTimer.Counter problemDrawMeasure = RenderPerfTimer.measure(RenderPerfTimer.Category.PROBLEM_DRAW);
		rankAndScoreMeasure.startMeasure();
		g.setFont(rankFont);
		FontMetrics rankFm = g.getFontMetrics();
		int ww = rankFm.stringWidth("199");

		g.setFont(penaltyFont);
		FontMetrics penaltyFm = g.getFontMetrics();
		int maxwid = tileDim.width - tileDim.height - ww - IN_TILE_GAP * 3 - 2 - penaltyFm.stringWidth("1999");

		// draw rank & score
		g.setFont(rankFont);
		g.setColor(lightMode ? Color.BLACK : Color.WHITE);

		IStanding standing = contest.getStanding(team);
		String s = standing.getRank();
		g.drawString(s, (ww - rankFm.stringWidth(s)) / 2, (tileDim.height + rankFm.getAscent()) / 2);
		rankAndScoreMeasure.stopMeasure();

		// draw name
		nameMeasure.startMeasure();
		paintName(g, team, ww, maxwid - 3);
		nameMeasure.stopMeasure();

		rankAndScoreMeasure.startMeasure();
		g.setFont(teamFont);
		FontMetrics teamFm = g.getFontMetrics();
		if (contest.getScoreboardType() == ScoreboardType.PASS_FAIL) {
			if (standing.getNumSolved() > 0) {
				s = standing.getNumSolved() + "";
				g.drawString(s, tileDim.width - IN_TILE_GAP * 2 - teamFm.stringWidth(s),
						(tileDim.height * 7 / 10 + teamFm.getAscent()) / 2 - 2);
			}

			g.setColor(lightMode ? Color.DARK_GRAY : Color.LIGHT_GRAY);
			g.setFont(penaltyFont);
			if (standing.getTime() > 0) {
				s = ContestUtil.getTime(standing.getTime());
				g.drawString(s, tileDim.width - IN_TILE_GAP * 2 - penaltyFm.stringWidth(s),
						tileDim.height * 17 / 20 + penaltyFm.getAscent() / 2 - 3);
			}
		} else {
			if (standing.getNumSolved() > 0) {
				s = ContestUtil.formatScore(standing.getScore());
				g.drawString(s, tileDim.width - IN_TILE_GAP * 2 - teamFm.stringWidth(s),
						(tileDim.height * 7 / 10 + teamFm.getAscent()) / 2 - 2);
			}
		}
		rankAndScoreMeasure.stopMeasure();

		// draw a rounded-rectangle representation for each problem
		problemMeasure.startMeasure();
		IProblem[] problems = contest.getProblems();
		int numProblems = problems.length;
		if (numProblems == 0)
			return;

		int y = tileDim.height * 7 / 10 - 3;
		int h = tileDim.height * 3 / 10;
		float w = (float) maxwid / (float) numProblems;
		int xx = ww + tileDim.height + IN_TILE_GAP;
		int arc = tileDim.width / 120;

		g.setFont(statusFont);
		FontMetrics statusFm = g.getFontMetrics();
		g.setFont(problemFont);
		FontMetrics problemFm = g.getFontMetrics();

		for (int i = 0; i < numProblems; i++) {
			IResult r = contest.getResult(team, i);
			if (!preRendering && !g.getClip().intersects(xx + (int) (w * i), y, w, h)) {
				continue;
			}
			String hash = r.getNumSubmissions() + "-" + ContestUtil.getTime(r.getContestTime()) + " "
					+ r.getStatus().name() + " " + (int) w;
			if (r.isFirstToSolve()) {
				hash += " FIRST";
			}
			if (r.getNumSubmissions() == 0) {
				inactiveProblemMeasure.startMeasure();
				String label = problems[i].getLabel();
				String problemOnlyHash = label + (int) w;
				BufferedImage img = problemImages.get(problemOnlyHash);
				if (img == null) {
					img = new BufferedImage((int) w, h, BufferedImage.TYPE_4BYTE_ABGR);
					Graphics2D gg = (Graphics2D) img.getGraphics();
					gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
					gg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
					paintProblem(gg, (int) w, h, arc, problemFm, label);
					gg.dispose();
					problemImages.put(problemOnlyHash, img);
					img = cacheMiss(img);
				}
				inactiveProblemMeasure.stopMeasure();
				problemDrawMeasure.startMeasure();
				g.drawImage(img, xx + (int) (w * i), y, null);
				problemDrawMeasure.stopMeasure();
			} else if (ContestUtil.isRecent(contest, r)) {
				activeProblemMeasure.startMeasure();
				int k = (int) ((timeMs * 45.0 / 1000.0) % (ICPCColors.COUNT2 * 2));
				String backHash = r.getStatus().name() + " " + (int) w + " flash " + k;
				BufferedImage backImg = getCacheOrRender(backHash, problemImages, (int) w, h, (gg) -> {
					paintRecentResultBackground(gg, k, r, 0, 0, (int) w, h, arc);
				});
				// TODO: only cache up to the natural string width
				String resultTextOnlyHash = hash + " TEXT";
				BufferedImage img = getCacheOrRender(resultTextOnlyHash, problemImages, (int) w, h, (gg) -> {
					gg.setFont(statusFont);
					paintResultText(gg, r, 0, 0, (int) w, h, arc, statusFm);
				});
				activeProblemMeasure.stopMeasure();
				problemDrawMeasure.startMeasure();
				g.drawImage(backImg, xx + (int) (w * i), y, null);
				g.drawImage(img, xx + (int) (w * i), y, null);
				problemDrawMeasure.stopMeasure();
			} else {
				inactive2ProblemMeasure.startMeasure();
				SoftReference<BufferedImage> ref = resultImages.get(hash);
				BufferedImage img = null;
				if (ref != null) {
					img = ref.get();
					if (img == null) {
						Trace.trace(Trace.INFO, "Soft image reference cleared: " + hash);
					}
				}
				if (img == null) {
					Trace.trace(Trace.INFO, "problem cache miss " + hash);
					img = new BufferedImage((int) w, h, BufferedImage.TYPE_4BYTE_ABGR);
					Graphics2D gg = (Graphics2D) img.getGraphics();
					gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
					gg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
					gg.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
					paintResult(gg, r, (int) w, h, arc, statusFm);
					gg.dispose();
					resultImages.put(hash, new SoftReference<BufferedImage>(img));
					img = cacheMiss(img);
				}
				inactive2ProblemMeasure.stopMeasure();
				problemDrawMeasure.startMeasure();
				g.drawImage(img, xx + (int) (w * i), y, null);
				problemDrawMeasure.stopMeasure();
			}
		}
		problemMeasure.stopMeasure();
	}

	private static BufferedImage getCacheOrRender(String hash, Map<String, BufferedImage> cache, int w, int h,
			Consumer<Graphics2D> renderer) {
		BufferedImage img = cache.get(hash);
		if (img == null) {
			Trace.trace(Trace.INFO, "cache miss " + hash);
			img = renderBufferedImage(w, h, renderer);
			cache.put(hash, img);
			img = cacheMiss(img);
		}
		return img;
	}

	private static BufferedImage renderBufferedImage(int w, int h, Consumer<Graphics2D> renderer) {
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_4BYTE_ABGR);
		Graphics2D gg = (Graphics2D) img.getGraphics();
		gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		gg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		gg.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		gg.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
		renderer.accept(gg);
		gg.dispose();
		return img;
	}

	private void paintProblem(Graphics2D g, int w, int h, int arc, FontMetrics fm, String label) {
		g.setColor(lightMode ? PROBLEM_BG_LIGHT : PROBLEM_BG);
		g.fillRoundRect(0, 0, w - 3, h - 1, arc, arc);
		g.setColor(lightMode ? Color.DARK_GRAY : Color.LIGHT_GRAY);
		g.setFont(problemFont);
		g.drawString(label, (w - fm.stringWidth(label)) / 2 - 1, (h + fm.getAscent()) / 2 - 1);
	}

	private static void paintRecentResultBackground(Graphics2D g, int kk, IResult r, int x, int y, int w, int h,
			int arc) {
		Color c = null;
		int k = kk;
		// flash more than once per second
		if (k > (ICPCColors.COUNT2 - 1))
			k = (ICPCColors.COUNT2 * 2 - 1) - k;

		if (r.getStatus() == Status.SOLVED) {
			if (r.isFirstToSolve())
				c = ICPCColors.FIRST_TO_SOLVE3[k];
			else
				c = ICPCColors.SOLVED3[k];
		} else if (r.getStatus() == Status.FAILED)
			c = ICPCColors.FAILED3[k];
		else if (r.getStatus() == Status.SUBMITTED)
			c = ICPCColors.PENDING3[k];

		g.setColor(c);
		g.fillRoundRect(x, y, w - 3, h - 1, arc, arc);
	}

	private void paintResultText(Graphics2D g, IResult r, int x, int y, int w, int h, int arc, FontMetrics fm) {
		g.setColor(lightMode ? Color.BLACK : Color.WHITE);
		String s = "";

		if (r.getNumSubmissions() > 0) {
			if (contest.getScoreboardType() == ScoreboardType.PASS_FAIL) {
				if (fm.stringWidth("9\u200A-\u200A999") > PROBLEM_TIME_SQUEEZE * w - 5)
					s = r.getNumSubmissions() + "";
				else
					s = r.getNumSubmissions() + "\u200A-\u200A" + ContestUtil.getTime(r.getContestTime());
			} else {
				if (r.getStatus() == Status.SOLVED)
					s = ContestUtil.formatScore(r.getScore());
				else
					s = r.getNumSubmissions() + "";
			}

			g.setFont(statusFont);
			// g.drawString(s, x + (w - fm.stringWidth(s)) / 2 - 1, y + (h + fm.getAscent()) / 2 - 1);
			// TODO: Use TextHelper.drawFit to fit text that does not fit in the available space
			// note: 9-999 is used to guess the space needed, but number of submissions may be above 9
			// and not fit.
			TextHelper text = new TextHelper(g, s);
			int sw = text.getWidth();
			text.drawFit(x + (w - Math.min(sw, w - 4)) / 2 - 2, y + (h - fm.getAscent()) / 2, w - 4);

			if (r.isFirstToSolve()) {
				g.setColor(ICPCColors.SOLVED_COLOR);
				g.drawRoundRect(x, y, w - 3, h - 1, arc, arc);
			}
		}
	}

	// TODO: remove the duplicate implementations
	private void paintResult(Graphics2D g, IResult r, int w, int h, int arc, FontMetrics fm) {
		Color c = null;
		if (r.getStatus() == Status.SOLVED) {
			if (r.isFirstToSolve())
				c = ICPCColors.FIRST_TO_SOLVE[5];
			else
				c = ICPCColors.SOLVED[5];
		} else if (r.getStatus() == Status.FAILED)
			c = ICPCColors.FAILED[5];
		else if (r.getStatus() == Status.SUBMITTED)
			c = ICPCColors.PENDING[5];

		g.setColor(c);
		g.fillRoundRect(0, 0, w - 3, h - 1, arc, arc);
		g.setColor(lightMode ? Color.BLACK : Color.WHITE);
		String s = "";

		if (r.getNumSubmissions() > 0) {
			if (contest.getScoreboardType() == ScoreboardType.PASS_FAIL) {
				if (fm.stringWidth("9\u200A-\u200A999") > PROBLEM_TIME_SQUEEZE * w - 5)
					s = r.getNumSubmissions() + "";
				else
					s = r.getNumSubmissions() + "\u200A-\u200A" + ContestUtil.getTime(r.getContestTime());
			} else {
				if (r.getStatus() == Status.SOLVED)
					s = ContestUtil.formatScore(r.getScore());
				else
					s = r.getNumSubmissions() + "";
			}

			g.setFont(statusFont);
			// g.drawString(s, (w - fm.stringWidth(s)) / 2 - 1, (h + fm.getAscent()) / 2 - 1);
			TextHelper text = new TextHelper(g, s);
			int sw = text.getWidth();
			text.drawFit((w - Math.min(sw, w - 4)) / 2 - 2, (h - fm.getAscent()) / 2, w - 4);

			if (r.isFirstToSolve()) {
				g.setColor(ICPCColors.SOLVED_COLOR);
				g.drawRoundRect(0, 0, w - 3, h - 1, arc, arc);
			}
		}
	}

	public void paintTileStats(Graphics2D g) {
		g.setFont(rankFont);
		FontMetrics fm = g.getFontMetrics();
		int ww = fm.stringWidth("199");

		g.setFont(penaltyFont);
		fm = g.getFontMetrics();

		// draw a rounded-rectangle representation for each problem
		IProblem[] problems = contest.getProblems();
		int numProblems = problems.length;
		if (numProblems == 0)
			return;

		int y = tileDim.height * 7 / 10;
		int h = tileDim.height * 3 / 10;
		int maxwid = tileDim.width - tileDim.height - ww - IN_TILE_GAP * 3 - 2 - fm.stringWidth("1999");
		float w = (float) maxwid / (float) numProblems;
		int xx = ww + tileDim.height + IN_TILE_GAP;
		int arc = tileDim.width / 120;

		g.setFont(statusFont);
		fm = g.getFontMetrics();

		for (int i = 0; i < numProblems; i++) {
			IProblemSummary ps = contest.getProblemSummary(i);
			if (ps.getNumPending() > 0)
				draw(g, ICPCColors.PENDING[5], xx + (int) (w * i), y - h * 5 / 4, (int) w, h, arc, ps.getNumPending() + "",
						fm);
			else
				draw(g, lightMode ? PROBLEM_BG_LIGHT : PROBLEM_BG, xx + (int) (w * i), y - h * 5 / 4, (int) w, h, arc, "",
						fm);

			if (ps.getNumSolved() > 0)
				draw(g, ICPCColors.SOLVED[5], xx + (int) (w * i), y, (int) w, h, arc, ps.getNumSolved() + "", fm);
			else
				draw(g, lightMode ? PROBLEM_BG_LIGHT : PROBLEM_BG, xx + (int) (w * i), y, (int) w, h, arc, "", fm);
		}
	}

	private void draw(Graphics2D g, Color c, int x, int y, int w, int h, int arc, String s, FontMetrics fm) {
		g.setColor(c);
		g.fillRoundRect(x, y, w - 3, h - 1, arc, arc);
		g.setColor(lightMode ? Color.BLACK : Color.WHITE);
		g.drawString(s, x + (w - fm.stringWidth(s)) / 2, y + (h + fm.getAscent()) / 2 - 1);
	}

	private static BufferedImage cacheMiss(BufferedImage img) {
		if (!PresentationWindowImpl.shouldColorCacheMisses()) {
			return img;
		}
		BufferedImage red = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_4BYTE_ABGR);
		Graphics2D g = red.createGraphics();
		final Color TRANSPARENT_RED = new Color(255, 0, 0, 192);
		g.drawImage(img, 0, 0, null);
		g.setColor(TRANSPARENT_RED);
		g.fillRect(0, 0, img.getWidth(), img.getHeight());
		g.dispose();
		return red;
	}
}
