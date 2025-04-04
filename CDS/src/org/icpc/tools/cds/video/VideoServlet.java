package org.icpc.tools.cds.video;

import java.io.IOException;
import java.io.OutputStream;

import org.icpc.tools.cds.CDSAuth;
import org.icpc.tools.cds.CDSConfig;
import org.icpc.tools.cds.ConfiguredContest;
import org.icpc.tools.cds.service.AppAsyncListener;
import org.icpc.tools.cds.video.VideoAggregator.ConnectionMode;
import org.icpc.tools.cds.video.VideoAggregator.Stats;
import org.icpc.tools.cds.video.VideoStream.StreamType;
import org.icpc.tools.contest.Trace;
import org.icpc.tools.contest.model.ContestUtil;
import org.icpc.tools.contest.model.IState;
import org.icpc.tools.contest.model.feed.JSONEncoder;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// stream/x - stream x
// stream/x?reset - reset stream x
// stream/channel/x - stream channel x
// stream/status - list status of all streams
// stream?team=x - action on streams for the given team id
// stream?type=x - action on streams of the given type
// stream?action=reset/eager/lazy/lazy_close - actions on streams
@WebServlet(urlPatterns = "/stream/*", asyncSupported = true)
public class VideoServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;

	private static final String RESET = "reset";
	private static final String ACTION = "action";
	private static final String TEAM = "team";
	private static final String TYPE = "type";

	private static VideoAggregator va = VideoAggregator.getInstance();

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		String path = request.getPathInfo();

		boolean channel = false;
		if (path == null || path.equals("/") || path.isEmpty()) {
			if (!CDSAuth.isAdmin(request)) {
				response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
				return;
			}
			String actionParam = request.getParameter(ACTION);
			if (actionParam == null) {
				response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
				return;
			}
			StreamType streamType = null;
			String typeParam = request.getParameter(TYPE);
			if (typeParam != null) {
				if ("desktop".equalsIgnoreCase(typeParam))
					streamType = StreamType.DESKTOP;
				else if ("webcam".equalsIgnoreCase(typeParam))
					streamType = StreamType.WEBCAM;
				else if ("audio".equalsIgnoreCase(typeParam))
					streamType = StreamType.AUDIO;
				else if ("other".equalsIgnoreCase(typeParam))
					streamType = StreamType.OTHER;

				if (streamType == null) {
					response.sendError(HttpServletResponse.SC_BAD_REQUEST);
					return;
				}
			}
			String teamParam = request.getParameter(TEAM);
			if ("reset".equals(actionParam)) {
				va.reset(teamParam, streamType);
			} else {
				ConnectionMode mode = VideoAggregator.getConnectionMode(actionParam);
				if (mode == null) {
					response.sendError(HttpServletResponse.SC_BAD_REQUEST);
					return;
				}
				va.setConnectionMode(teamParam, streamType, mode);
			}

			return;
		} else if (path.startsWith("/status")) {
			response.setContentType("application/json");
			JSONEncoder je = new JSONEncoder(response.getWriter());
			writeStatus(je);
			return;
		} else if (path.startsWith("/channel")) {
			path = path.substring(8);
			channel = true;
		}

		String subpath = null;
		int stream = -1;
		try {
			int ind = path.indexOf("/", 1);
			if (ind >= 0) {
				subpath = path.substring(ind + 1);
				path = path.substring(0, ind);
			}
			stream = Integer.parseInt(path.substring(1));

			if (!channel) {
				if (stream < 0 || stream >= va.getNumStreams()) {
					response.sendError(HttpServletResponse.SC_NOT_FOUND);
					return;
				}
			} else {
				if (stream < 0 || stream >= VideoAggregator.MAX_CHANNELS) {
					response.sendError(HttpServletResponse.SC_NOT_FOUND);
					return;
				}
			}
		} catch (Exception e) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Not a valid request!");
			return;
		}

		String reset = request.getParameter(RESET);
		if (reset != null) {
			if (!CDSAuth.isAdmin(request)) {
				response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
				return;
			}
			VideoAggregator.getInstance().reset(stream);
			return;
		}

		// clients don't have this URL for direct connections, but if they try the pattern just
		// redirect them
		VideoStream vs = va.getStream(stream);
		if (ConnectionMode.DIRECT.equals(vs.getMode())) {
			response.sendRedirect(vs.getURL());
			return;
		}

		// check if any contests are in freeze
		boolean isStaff = CDSAuth.isStaff(request);
		if (!isStaff) {
			for (ConfiguredContest cc : CDSConfig.getContests()) {
				IState state = cc.getContest().getState();
				if (state.isFrozen() && state.isRunning()) {
					response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Contest is frozen");
					return;
				}
			}
		}

		// increment stats
		if (stream >= -1) {
			StreamType st = va.getStreamType(stream);
			for (ConfiguredContest cc : CDSConfig.getContests()) {
				// if (cc.getContest().getTeamById(videoId) != null) { // TODO 3 per team
				if (st == StreamType.DESKTOP)
					cc.incrementDesktop();
				else if (st == StreamType.WEBCAM)
					cc.incrementWebcam();
				else if (st == StreamType.AUDIO)
					cc.incrementAudio();
			}
		}

		if (VideoAggregator.handler instanceof VideoServingHandler) {
			((VideoServingHandler) VideoAggregator.handler).doGet(request, response, stream, vs, subpath);
		} else {
			streamVideo(request, response, stream, vs, channel, isStaff);
		}
	}

	public static void streamVideo(HttpServletRequest request, HttpServletResponse response, final int stream,
			VideoStream vs, boolean channel, boolean isStaff) throws IOException {

		Trace.trace(Trace.INFO, "Video request: " + request.getRemoteUser() + " requesting video " + stream + " -> "
				+ vs.getName() + " (channel: " + channel + ")");

		response.setHeader("Cache-Control", "no-cache");
		response.setHeader("Access-Control-Allow-Origin", "*");
		response.setHeader("X-Accel-Buffering", "no");
		response.setContentType(vs.getMimeType());
		response.setHeader("Content-Disposition",
				"inline; filename=\"stream-" + stream + "." + vs.getFileExtension() + "\"");

		OutputStream out = response.getOutputStream();
		final VideoStreamListener listener = new VideoStreamListener(out, isStaff);
		if (!channel)
			vs.addListener(listener);
		else
			va.addChannelListener(stream, listener);

		final AsyncContext asyncCtx = request.startAsync();
		asyncCtx.addListener(new AppAsyncListener() {
			@Override
			public void onComplete(AsyncEvent asyncEvent) throws IOException {
				if (!channel)
					vs.removeListener(listener);
				else
					va.removeChannelListener(stream, listener);
			}
		});
		asyncCtx.setTimeout(0);
	}

	private static void writeStatus(JSONEncoder je) {
		je.open();
		je.openChildArray("streams");
		int c = 0;
		for (VideoStream vi : va.getVideoInfo()) {
			je.open();
			je.encode("id", c++ + "");
			je.encode("name", vi.getName());
			je.encode("type", vi.getType().name());
			je.encode("team_id", vi.getTeamId());
			je.encode("mode", vi.getMode().name());
			je.encode("status", vi.getStatus().name());
			Stats s = vi.getStats();
			je.encode("current", s.currentListeners);
			je.encode("max_current", s.maxConcurrentListeners);
			je.encode("total_listeners", s.totalListeners);
			je.encode("total_time", ContestUtil.formatTime(s.totalTime));
			je.close();
		}
		je.closeArray();

		je.encode("current", va.getConcurrent());
		je.encode("max_current", va.getMaxConcurrent());
		je.encode("total_listeners", va.getTotal());
		je.encode("total_time", ContestUtil.formatTime(va.getTotalTime()));
		je.close();
	}
}