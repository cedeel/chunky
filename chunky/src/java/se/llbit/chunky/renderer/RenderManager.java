/* Copyright (c) 2012-2014 Jesper Öqvist <jesper@llbit.se>
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
package se.llbit.chunky.renderer;

import java.awt.FileDialog;
import java.awt.Graphics;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.log4j.Logger;

import se.llbit.chunky.renderer.scene.Scene;
import se.llbit.chunky.renderer.scene.SceneLoadingError;
import se.llbit.chunky.ui.CenteredFileDialog;
import se.llbit.chunky.world.ChunkPosition;
import se.llbit.chunky.world.World;

/**
 * Manages the 3D render worker threads.
 *
 * @author Jesper Öqvist <jesper@llbit.se>
 */
public class RenderManager extends AbstractRenderManager implements Renderer {

	private static final Logger logger =
			Logger.getLogger(RenderManager.class);

	/**
	 * Milliseconds until the reset confirmation must be shown.
	 */
	private static final long SCENE_EDIT_GRACE_PERIOD = 30000;

	private boolean updateBuffer = false;
	private boolean dumpNextFrame = false;

	private final RenderableCanvas canvas;
	private Thread[] workers = {};

	private int numJobs;

	/**
 	 * The modifiable scene.
 	 */
	private final Scene mutableScene;

	/**
 	 * The buffered scene is only updated between
 	 * render jobs.
 	 */
	private final Scene bufferedScene;

	/**
	 * Next job on the job queue.
	 */
	private final AtomicInteger nextJob;

	/**
	 * Number of completed jobs.
	 */
	private final AtomicInteger finishedJobs;

	private final RenderContext context;

	private final RenderStatusListener renderListener;

	private final Object bufferMonitor = new Object();

	private final boolean oneshot;

	private boolean pathTracing = false;
	private boolean paused = true;

	/**
	 * Constructor
	 * @param canvas
	 * @param context
	 * @param statusListener
	 */
	public RenderManager(RenderableCanvas canvas, RenderContext context,
			RenderStatusListener statusListener) {
		this(canvas, context, statusListener, false);
	}

	/**
	 * Constructor
	 * @param canvas
	 * @param context
	 * @param statusListener
	 * @param oneshot <code>true</code> if rendering threads should be shut
	 * down after meeting the render target
	 */
	public RenderManager(RenderableCanvas canvas, RenderContext context,
			RenderStatusListener statusListener, boolean oneshot) {

		super(context);

		this.canvas = canvas;
		this.context = context;
		this.oneshot = oneshot;
		renderListener = statusListener;

		mutableScene = new Scene();
		bufferedScene = new Scene(mutableScene);

		numJobs = 0;
		nextJob = new AtomicInteger(0);
		finishedJobs = new AtomicInteger(0);

		manageWorkers();
	}

	private void manageWorkers() {
		if (numThreads != workers.length) {
			long seed = System.currentTimeMillis();
			Thread[] pool = new Thread[numThreads];
			int i;
			for (i = 0; i < workers.length && i < numThreads; ++i) {
				pool[i] = workers[i];
			}
			// start additional workers
			for (; i < numThreads; ++i) {
				pool[i] = new RenderWorker(this, i, seed+i);
				pool[i].start();
			}
			// stop extra workers
			for (; i < workers.length; ++i) {
				workers[i].interrupt();
			}
			workers = pool;
		}
	}

	@Override
	public void run() {
		try {

			while (!isInterrupted()) {

				boolean refreshed = mutableScene.waitOnRefreshOrStateChange();

				synchronized (bufferMonitor) {
					synchronized (mutableScene) {
						updateRenderState();
						bufferedScene.copyRenderState(mutableScene);
						if (refreshed && !mutableScene.shouldReset() &&
								bufferedScene.renderTime > SCENE_EDIT_GRACE_PERIOD) {
							renderListener.renderResetPrevented();
						} else {
							bufferedScene.set(mutableScene);
						}
					}
				}

				if (bufferedScene.pathTrace()) {
					pathTraceLoop();
				} else {
					previewLoop();
				}

				if (oneshot) {
					break;
				}
			}
		} catch (InterruptedException e) {
			// 3D view was closed
		} catch (Throwable e) {
			logger.error("Uncaught exception in render manager", e);
		}

		stopWorkers();
	}

	private void updateRenderState() {
		if (pathTracing != mutableScene.pathTrace() || paused != mutableScene.isPaused()) {
			pathTracing = mutableScene.pathTrace();
			paused = mutableScene.isPaused();
			renderListener.renderStateChanged(pathTracing, paused);
		}
	}

	private void pathTraceLoop() throws InterruptedException {
		// enable JIT for the first frame
		java.lang.Compiler.enable();

		while (true) {

			if (mutableScene.isPaused()) {
				updateRenderState();
				mutableScene.pauseWait();
				updateRenderState();
			}

			if (mutableScene.shouldRefresh()) {
				return;
			}

			synchronized (bufferMonitor) {
				long frameStart = System.currentTimeMillis();
				giveTickets();
				waitOnWorkers();
				bufferedScene.updateCanvas();
				bufferedScene.renderTime += System.currentTimeMillis() - frameStart;
			}

			// repaint canvas
			canvas.repaint();

			// disable JIT for subsequent frames
			java.lang.Compiler.disable();

			bufferedScene.spp += RenderConstants.SPP_PASS;

			int canvasWidth = bufferedScene.canvasWidth();
			int canvasHeight = bufferedScene.canvasHeight();
			long pixelsPerFrame = canvasWidth * canvasHeight;
			double samplesPerSecond = (bufferedScene.spp * pixelsPerFrame) /
					(bufferedScene.renderTime / 1000.0);

			// Update render status display
			renderListener.setRenderTime(bufferedScene.renderTime);
			renderListener.setSamplesPerSecond((int) samplesPerSecond);
			renderListener.setSPP(bufferedScene.spp);

			// Notify progress listener
			int target = bufferedScene.getTargetSPP();
			long etaSeconds = (long) (((target-bufferedScene.spp) *
					pixelsPerFrame) / samplesPerSecond);
			int seconds = (int) ((etaSeconds) % 60);
			int minutes = (int) ((etaSeconds / 60) % 60);
			int hours = (int) (etaSeconds / 3600);
			String eta = String.format("%d:%02d:%02d", hours, minutes, seconds);
			renderListener.setProgress("Rendering", bufferedScene.spp,
					0, target, eta);

			if (dumpNextFrame) {
				// save the current frame
				if (mutableScene.shouldSaveSnapshots() ||
						bufferedScene.spp >= bufferedScene.getTargetSPP()) {
					bufferedScene.saveSnapshot(context.getSceneDirectory(), renderListener);
				}

				// save scene description and render dump
				saveScene();
				renderListener.setProgress("Rendering", bufferedScene.spp,
						0, target, eta);
			}

			if (bufferedScene.spp >= bufferedScene.getTargetSPP()) {
				mutableScene.pauseRender();
				renderListener.renderStateChanged(mutableScene.pathTrace(), mutableScene.isPaused());
				renderListener.renderJobFinished(bufferedScene.renderTime,
						(int) samplesPerSecond);
				if (oneshot) {
					return;
				}
			}
		}


	}

	private void previewLoop() throws InterruptedException {
		long frameStart;

		renderListener.setProgress("Preview", 0, 0, 2);
		bufferedScene.previewCount = 2;

		while (true) {
			if (!updateBuffer ||
					bufferedScene.previewCount <= 0 ||
					mutableScene.shouldRefresh()) {

				return;
			}

			synchronized (bufferMonitor) {
				frameStart = System.currentTimeMillis();
				giveTickets();
				waitOnWorkers();
				bufferedScene.updateCanvas();
				bufferedScene.renderTime += System.currentTimeMillis() - frameStart;
			}

			// repaint canvas
			canvas.repaint();

			bufferedScene.previewCount -= 1;
			bufferedScene.spp = 0;

			// Update render status display
			renderListener.setRenderTime(bufferedScene.renderTime);
			renderListener.setSamplesPerSecond(0);
			renderListener.setSPP(0);

			// Notify progress listener
			renderListener.setProgress("Preview", 2 - bufferedScene.previewCount, 0, 2);
		}
	}

	private void backupFile(String fileName) {
		File renderDir = context.getSceneDirectory();
		File file = new File(renderDir, fileName);
		backupFile(file);
	}

	private void backupFile(File file) {
		if (file.exists()) {
			// try to create backup
			// it's not a biggie if we can't
			String backupFileName = file.getName() + ".backup";
			File renderDir = context.getSceneDirectory();
			File backup = new File(renderDir, backupFileName);
			if (backup.exists())
				backup.delete();
			if (!file.renameTo(new File(renderDir, backupFileName)))
				logger.info("Could not create backup " + backupFileName);
		}

	}

	private synchronized void waitOnWorkers() throws InterruptedException {
		while (finishedJobs.get() < numJobs)
			wait();
		// all workers finished - we can now change the number of worker threads!
		manageWorkers();
	}

	private synchronized void giveTickets() {
		bufferedScene.copyTransients(mutableScene);
		int nextSpp = bufferedScene.spp + RenderConstants.SPP_PASS;
		dumpNextFrame = nextSpp >= bufferedScene.getTargetSPP() ||
				bufferedScene.shouldSaveDumps() &&
				(nextSpp % bufferedScene.getDumpFrequency() == 0);
		bufferedScene.setBufferFinalization(updateBuffer || dumpNextFrame);

		int canvasWidth = bufferedScene.canvasWidth();
		int canvasHeight = bufferedScene.canvasHeight();
		numJobs = ((canvasWidth+(tileWidth-1)) / tileWidth) *
				((canvasHeight+(tileWidth-1)) / tileWidth);
		nextJob.set(0);
		finishedJobs.set(0);
		notifyAll();
	}

	@Override
	public int getNextJob() throws InterruptedException {
		int jobId = nextJob.getAndIncrement();
		if (jobId >= numJobs) {
			synchronized (this) {
				do {
					wait();
					jobId = nextJob.getAndIncrement();
				} while (jobId >= numJobs);
			}
		}
		return jobId;
	}

	@Override
	public void jobDone() {
		int finished = finishedJobs.incrementAndGet();
		if (finished >= numJobs) {
			synchronized (this) {
				notifyAll();
			}
		}
	}

	/**
	 * Save the current scene
	 * @throws InterruptedException
	 */
	public void saveScene() throws InterruptedException {

		try {
			synchronized (bufferMonitor) {
				String sceneName = bufferedScene.name();
				logger.info("Saving scene " + sceneName);

				// create backup of scene description and current render dump
				backupFile(context.getSceneDescriptionFile(sceneName));
				backupFile(mutableScene.name() + ".dump");

				// synchronize the transients
				bufferedScene.copyTransients(mutableScene);

				bufferedScene.saveScene(context, renderListener);

				logger.info("Scene saved");
			}

			renderListener.sceneSaved();
		} catch (IOException e) {
			logger.warn("Failed to save scene. Reason: " + e.getMessage(), e);
		}
	}

	/**
	 * Load a saved scene
	 * @param sceneName
	 * @throws IOException
	 * @throws SceneLoadingError
	 * @throws InterruptedException
	 */
	public void loadScene(String sceneName)
			throws IOException, SceneLoadingError, InterruptedException {

		synchronized (bufferMonitor) {
			renderListener.setProgress("Loading scene", 0, 0, 1);
			try {
				bufferedScene.loadScene(context, renderListener, sceneName);
			} catch (InterruptedException e) {
				renderListener.taskFailed("Loading scene");
				throw e;
			} catch (SceneLoadingError e) {
				renderListener.taskFailed("Loading scene");
				throw e;
			} catch (IOException e) {
				renderListener.taskFailed("Loading scene");
				throw e;
			}

			synchronized (this) {
				int canvasWidth = bufferedScene.canvasWidth();
				int canvasHeight = bufferedScene.canvasHeight();
				numJobs = canvasWidth * canvasHeight;
			}

			// Update progress bar
			renderListener.setProgress("Rendering",
					bufferedScene.spp, 0,
					bufferedScene.getTargetSPP());

			synchronized (mutableScene) {
				// synchronized to ensure that refresh flag is never visibly true
				mutableScene.set(bufferedScene);
				mutableScene.copyRenderState(bufferedScene);
				mutableScene.copyTransients(bufferedScene);
				mutableScene.setRefreshed();
			}
			bufferedScene.updateCanvas();
			canvas.repaint();

			renderListener.sceneLoaded();
			renderListener.renderStateChanged(mutableScene.pathTrace(), mutableScene.isPaused());
		}
	}

	/**
	 * Default directory for "save current frame" file dialog.
	 */
	private static String defaultDirectory = System.getProperty("user.dir");

	/**
	 * Save the current frame as a PNG image.
	 * @param progressListener
	 */
	public synchronized void saveSnapshot(ProgressListener progressListener) {

		CenteredFileDialog fileDialog = new CenteredFileDialog(null,
				"Save Current Frame", FileDialog.SAVE);
		String directory;
		synchronized (RenderManager.class) {
			directory = defaultDirectory;
		}
		fileDialog.setDirectory(directory);
		fileDialog.setFile(bufferedScene.name()+"-"+bufferedScene.spp+".png");
		fileDialog.setFilenameFilter(
			new FilenameFilter() {
				@Override
				public boolean accept(File dir, String name) {
					return name.toLowerCase().endsWith(".png");
				}
			}
		);
		fileDialog.setVisible(true);
		File selectedFile = fileDialog.getSelectedFile(".png");
		if (selectedFile != null) {
			synchronized (RenderManager.class) {
				File parent = selectedFile.getParentFile();
				if (parent != null) {
					defaultDirectory = parent.getAbsolutePath();
				}
			}
			try {
				bufferedScene.saveFrame(selectedFile, progressListener);
			} catch (IOException e) {
				logger.error("Failed to save snapshot", e);
			}
		}
	}

	/**
	 * @return The current scene object
	 */
	public Scene scene() {
		return mutableScene;
	}

	@Override
	public void drawBufferedImage(Graphics g, int width, int height) {
		bufferedScene.drawBufferedImage(g, width, height);
	}

	@Override
	public synchronized void setBufferFinalization(boolean flag) {
		if (flag != updateBuffer) {
			updateBuffer = flag;
			if (flag) {
				synchronized (mutableScene) {
					if (!mutableScene.pathTrace())
						mutableScene.refresh();
				}
				logger.debug("buffer finalization enabled");
			} else {
				logger.debug("buffer finalization disabled");
			}
		}
	}

	/**
	 * Load chunks and reset camera
	 * @param world
	 * @param chunksToLoad
	 */
	public void loadFreshChunks(World world, Collection<ChunkPosition> chunksToLoad) {
		mutableScene.loadChunks(renderListener, world, chunksToLoad);
		mutableScene.moveCameraToCenter();
		renderListener.chunksLoaded();
	}

	/**
	 * Load chunks without moving the camera
	 * @param world
	 * @param chunksToLoad
	 */
	public void loadChunks(World world, Collection<ChunkPosition> chunksToLoad) {
		mutableScene.loadChunks(renderListener, world, chunksToLoad);
		mutableScene.refresh();
		renderListener.chunksLoaded();
	}

	/**
	 * Attempts to reload all loaded chunks
	 */
	public void reloadChunks() {
		mutableScene.reloadChunks(renderListener);
		renderListener.chunksLoaded();
	}

	@Override
	public Scene bufferedScene() {
		return bufferedScene;
	}

	/**
	 * Merge a render dump into the current render
	 * @param dumpFile
	 */
	public void mergeDump(File dumpFile) {
		bufferedScene.mergeDump(dumpFile, renderListener);
		bufferedScene.updateCanvas();
		canvas.repaint();
	}

	/**
	 * Change number of render workers
	 * @param threads
	 */
	public void setNumThreads(int threads) {
		numThreads = Math.max(1, threads);
	}

	/**
	 * Set CPU load percentage
	 * @param value
	 */
	public void setCPULoad(int value) {
		cpuLoad  = value;
	}

	/**
	 * Stop the render workers.
	 */
	private synchronized void stopWorkers() {
		// Halt all worker threads
		for (int i = 0; i < numThreads; ++i) {
			workers[i].interrupt();
		}
	}

	public void revertPendingSceneChanges() {
		synchronized (mutableScene) {
			// synchronized to ensure that refresh flag is never visibly true
			mutableScene.set(bufferedScene);
			mutableScene.setRefreshed();// clear refresh flag
		}
	}
}
