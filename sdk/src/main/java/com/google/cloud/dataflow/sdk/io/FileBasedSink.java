/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.dataflow.sdk.io;

import com.google.api.client.googleapis.batch.BatchRequest;
import com.google.api.client.googleapis.batch.json.JsonBatchCallback;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.services.storage.Storage;
import com.google.api.services.storage.StorageRequest;
import com.google.api.services.storage.model.StorageObject;
import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.coders.SerializableCoder;
import com.google.cloud.dataflow.sdk.options.GcsOptions;
import com.google.cloud.dataflow.sdk.options.PipelineOptions;
import com.google.cloud.dataflow.sdk.transforms.DoFn;
import com.google.cloud.dataflow.sdk.transforms.GroupByKey;
import com.google.cloud.dataflow.sdk.transforms.PTransform;
import com.google.cloud.dataflow.sdk.transforms.ParDo;
import com.google.cloud.dataflow.sdk.transforms.windowing.DefaultTrigger;
import com.google.cloud.dataflow.sdk.transforms.windowing.GlobalWindows;
import com.google.cloud.dataflow.sdk.transforms.windowing.Window;
import com.google.cloud.dataflow.sdk.util.FileIOChannelFactory;
import com.google.cloud.dataflow.sdk.util.GcsIOChannelFactory;
import com.google.cloud.dataflow.sdk.util.IOChannelFactory;
import com.google.cloud.dataflow.sdk.util.IOChannelUtils;
import com.google.cloud.dataflow.sdk.util.MimeTypes;
import com.google.cloud.dataflow.sdk.util.Transport;
import com.google.cloud.dataflow.sdk.util.gcsfs.GcsPath;
import com.google.cloud.dataflow.sdk.values.KV;
import com.google.cloud.dataflow.sdk.values.PCollection;
import com.google.cloud.hadoop.util.ApiErrorExtractor;
import com.google.common.base.Preconditions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * Abstract {@link Sink} for file-based output. An implementation of FileBasedSink writes file-based
 * output and defines the format of output files (how values are written, headers/footers, MIME
 * type, etc.).
 *
 * <p>At pipeline construction time, the methods of FileBasedSink are called to validate the sink
 * and to create a {@link Sink.WriteOperation} that manages the process of writing to the sink.
 *
 * <p>The process of writing to file-based sink is as follows:
 * <ol>
 * <li>An optional subclass-defined initialization,
 * <li>a parallel write of bundles to temporary files, and finally,
 * <li>these temporary files are renamed with final output filenames.
 * </ol>
 *
 * <p>Supported file systems are those registered with {@link IOChannelUtils}.
 *
 * @param <T> the type of values written to the sink.
 */
public abstract class FileBasedSink<T> extends Sink<T> {
  /**
   * Base filename for final output files.
   */
  protected final String baseOutputFilename;

  /**
   * The extension to be used for the final output files.
   */
  protected final String extension;

  /**
   * Naming template for output files. See {@link ShardNameTemplate} for a description of
   * possible naming templates.  Default is {@link ShardNameTemplate#INDEX_OF_MAX}.
   */
  protected final String fileNamingTemplate;

  /**
   * Construct a FileBasedSink with the given base output filename and extension.
   */
  public FileBasedSink(String baseOutputFilename, String extension) {
    this(baseOutputFilename, extension, ShardNameTemplate.INDEX_OF_MAX);
  }

  /**
   * Construct a FileBasedSink with the given base output filename, extension, and file naming
   * template.
   *
   * <p>See {@link ShardNameTemplate} for a description of file naming templates.
   */
  public FileBasedSink(String baseOutputFilename, String extension, String fileNamingTemplate) {
    this.baseOutputFilename = baseOutputFilename;
    this.extension = extension;
    this.fileNamingTemplate = fileNamingTemplate;
  }

  /**
   * Returns the base output filename for this file based sink.
   */
  public String getBaseOutputFilename() {
    return baseOutputFilename;
  }

  /**
   * Perform pipeline-construction-time validation. The default implementation is a no-op.
   * Subclasses should override to ensure the sink is valid and can be written to. It is recommended
   * to use {@link Preconditions} in the implementation of this method.
   */
  @Override
  public void validate(PipelineOptions options) {}

  /**
   * Return a subclass of {@link FileBasedSink.FileBasedWriteOperation} that will manage the write
   * to the sink.
   */
  @Override
  public abstract FileBasedWriteOperation<T> createWriteOperation(PipelineOptions options);

  /**
   * Abstract {@link Sink.WriteOperation} that manages the process of writing to a
   * {@link FileBasedSink}.
   *
   * <p>The primary responsibilities of the FileBasedWriteOperation is the management of output
   * files. During a write, {@link FileBasedSink.FileBasedWriter}s write bundles to temporary file
   * locations. After the bundles have been written,
   * <ol>
   * <li>{@link FileBasedSink.FileBasedWriteOperation#finalize} is given a list of the temporary
   * files containing the output bundles.
   * <li>During finalize, these temporary files are copied to final output locations and named
   * according to a file naming template.
   * <li>Finally, any temporary files that were created during the write are removed.
   * </ol>
   *
   * <p>Subclass implementations of FileBasedWriteOperation must implement
   * {@link FileBasedSink.FileBasedWriteOperation#createWriter} to return a concrete
   * FileBasedSinkWriter.
   *
   * <h2>Temporary and Output File Naming:</h2> During the write, bundles are written to temporary
   * files using the baseTemporaryFilename that can be provided via the constructor of
   * FileBasedWriteOperation. These temporary files will be named
   * {@code {baseTemporaryFilename}-temp-{bundleId}}, where bundleId is the unique id of the bundle.
   * For example, if baseTemporaryFilename is "gs://my-bucket/my_temp_output", the output for a
   * bundle with bundle id 15723 will be "gs://my-bucket/my_temp_output-temp-15723".
   *
   * <p>Final output files are written to baseOutputFilename with the format
   * {@code {baseOutputFilename}-0000i-of-0000n.{extension}} where n is the total number of bundles
   * written and extension is the file extension. Both baseOutputFilename and extension are required
   * constructor arguments.
   *
   * <p>Subclass implementations can change the file naming template by supplying a value for
   * {@link FileBasedSink#fileNamingTemplate}.
   *
   * <h2>Temporary Bundle File Handling:</h2>
   * <p>{@link FileBasedSink.FileBasedWriteOperation#temporaryFileRetention} controls the behavior
   * for managing temporary files. By default, temporary files will be removed. Subclasses can
   * provide a different value to the constructor.
   *
   * <p>Note that in the case of permanent failure of a bundle's write, no clean up of temporary
   * files will occur.
   *
   * <p>If there are no elements in the PCollection being written, no output will be generated.
   *
   * @param <T> the type of values written to the sink.
   */
  public abstract static class FileBasedWriteOperation<T> extends WriteOperation<T, FileResult> {
    private static final Logger LOG = LoggerFactory.getLogger(FileBasedWriteOperation.class);

    /**
     * Options for handling of temporary output files.
     */
    public enum TemporaryFileRetention {
      KEEP,
      REMOVE;
    }

    /**
     * The Sink that this WriteOperation will write to.
     */
    protected final FileBasedSink<T> sink;

    /**
     * Option to keep or remove temporary output files.
     */
    protected final TemporaryFileRetention temporaryFileRetention;

    /**
     * Base filename used for temporary output files. Default is the baseOutputFilename.
     */
    protected final String baseTemporaryFilename;

    /**
     * Name separator for temporary files. Temporary files will be named
     * {@code {baseTemporaryFilename}-temp-{bundleId}}.
     */
    protected static final String TEMPORARY_FILENAME_SEPARATOR = "-temp-";

    /**
     * Build a temporary filename using the temporary filename separator with the given prefix and
     * suffix.
     */
    protected static final String buildTemporaryFilename(String prefix, String suffix) {
      return prefix + FileBasedWriteOperation.TEMPORARY_FILENAME_SEPARATOR + suffix;
    }

    /**
     * Construct a FileBasedWriteOperation using the same base filename for both temporary and
     * output files.
     *
     * @param sink the FileBasedSink that will be used to configure this write operation.
     */
    public FileBasedWriteOperation(FileBasedSink<T> sink) {
      this(sink, sink.baseOutputFilename);
    }

    /**
     * Construct a FileBasedWriteOperation.
     *
     * @param sink the FileBasedSink that will be used to configure this write operation.
     * @param baseTemporaryFilename the base filename to be used for temporary output files.
     */
    public FileBasedWriteOperation(FileBasedSink<T> sink, String baseTemporaryFilename) {
      this(sink, baseTemporaryFilename, TemporaryFileRetention.REMOVE);
    }

    /**
     * Create a new FileBasedWriteOperation.
     *
     * @param sink the FileBasedSink that will be used to configure this write operation.
     * @param baseTemporaryFilename the base filename to be used for temporary output files.
     * @param temporaryFileRetention defines how temporary files are handled.
     */
    public FileBasedWriteOperation(FileBasedSink<T> sink, String baseTemporaryFilename,
        TemporaryFileRetention temporaryFileRetention) {
      this.sink = sink;
      this.baseTemporaryFilename = baseTemporaryFilename;
      this.temporaryFileRetention = temporaryFileRetention;
    }

    /**
     * Clients must implement to return a subclass of {@link FileBasedSink.FileBasedWriter}. This
     * method must satisfy the restrictions placed on implementations of
     * {@link Sink.WriteOperation#createWriter}. Namely, it must not mutate the state of the object.
     */
    @Override
    public abstract FileBasedWriter<T> createWriter(PipelineOptions options) throws Exception;

    /**
     * Initialization of the sink. Default implementation is a no-op. May be overridden by subclass
     * implementations to perform initialization of the sink at pipeline runtime. This method must
     * be idempotent and is subject to the same implementation restrictions as
     * {@link Sink.WriteOperation#initialize}.
     */
    @Override
    public void initialize(PipelineOptions options) throws Exception {}

    /**
     * Finalizes writing by copying temporary output files to their final location and optionally
     * removing temporary files.
     *
     * <p>Finalization may be overridden by subclass implementations to perform customized
     * finalization (e.g., initiating some operation on output bundles, merging them, etc.).
     * {@code writerResults} contains the filenames of written bundles.
     *
     * <p>If subclasses override this method, they must guarantee that its implementation is
     * idempotent, as it may be executed multiple times in the case of failure or for redundancy. It
     * is a best practice to attempt to try to make this method atomic.
     *
     * @param writerResults the results of writes (FileResult).
     */
    @Override
    public void finalize(Iterable<FileResult> writerResults, PipelineOptions options)
        throws Exception {
      // Collect names of temporary files and rename them.
      List<String> files = new ArrayList<>();
      for (FileResult result : writerResults) {
        LOG.debug("Temporary bundle output file {} will be copied.", result.getFilename());
        files.add(result.getFilename());
      }
      copyToOutputFiles(files, options);

      // Optionally remove temporary files.
      if (temporaryFileRetention == TemporaryFileRetention.REMOVE) {
        removeTemporaryFiles(options);
      }
    }

    /**
     * Copy temporary files to final output filenames using the file naming template.
     *
     * <p>Can be called from subclasses that override {@link FileBasedWriteOperation#finalize}.
     *
     * <p>Files will be named according to the file naming template. The order of the output files
     * will be the same as the sorted order of the input filenames.  In other words, if the input
     * filenames are ["C", "A", "B"], baseOutputFilename is "file", the extension is ".txt", and
     * the fileNamingTemplate is "-SSS-of-NNN", the contents of A will be copied to
     * file-000-of-003.txt, the contents of B will be copied to file-001-of-003.txt, etc.
     *
     * @param filenames the filenames of temporary files.
     * @return a list containing the names of final output files.
     */
    protected final List<String> copyToOutputFiles(List<String> filenames, PipelineOptions options)
        throws IOException {
      int numFiles = filenames.size();
      List<String> srcFilenames = new ArrayList<>();
      List<String> destFilenames = generateDestinationFilenames(numFiles);

      // Sort files for copying.
      srcFilenames.addAll(filenames);
      Collections.sort(srcFilenames);

      if (numFiles > 0) {
        LOG.debug("Copying {} files.", numFiles);
        FileOperations fileOperations =
            FileOperationsFactory.getFileOperations(destFilenames.get(0), options);
        fileOperations.copy(srcFilenames, destFilenames);
      } else {
        LOG.info("No output files to write.");
      }

      return destFilenames;
    }

    /**
     * Generate output bundle filenames.
     */
    protected final List<String> generateDestinationFilenames(int numFiles) {
      List<String> destFilenames = new ArrayList<>();
      String extension = getSink().extension;
      String baseOutputFilename = getSink().baseOutputFilename;
      String fileNamingTemplate = getSink().fileNamingTemplate;

      String suffix = getFileExtension(extension);
      for (int i = 0; i < numFiles; i++) {
        destFilenames.add(IOChannelUtils.constructName(
            baseOutputFilename, fileNamingTemplate, suffix, i, numFiles));
      }
      return destFilenames;
    }

    /**
     * Returns the file extension to be used. If the user did not request a file
     * extension then this method returns the empty string. Otherwise this method
     * adds a {@code "."} to the beginning of the users extension if one is not present.
     */
    private String getFileExtension(String usersExtension) {
      if (usersExtension == null || usersExtension.isEmpty()) {
        return "";
      }
      if (usersExtension.startsWith(".")) {
        return usersExtension;
      }
      return "." + usersExtension;
    }

    /**
     * Removes temporary output files. Uses the temporary filename to find files to remove.
     *
     * <p>Can be called from subclasses that override {@link FileBasedWriteOperation#finalize}.
     * <b>Note:</b>If finalize is overridden and does <b>not</b> rename or otherwise finalize
     * temporary files, this method will remove them.
     */
    protected final void removeTemporaryFiles(PipelineOptions options) throws IOException {
      String pattern = buildTemporaryFilename(baseTemporaryFilename, "*");
      LOG.debug("Finding temporary bundle output files matching {}.", pattern);
      FileOperations fileOperations = FileOperationsFactory.getFileOperations(pattern, options);
      IOChannelFactory factory = IOChannelUtils.getFactory(pattern);
      Collection<String> matches = factory.match(pattern);
      LOG.debug("{} temporary files matched {}", matches.size(), pattern);
      LOG.debug("Removing {} files.", matches.size());
      fileOperations.remove(matches);
    }

    /**
     * Provides a coder for {@link FileBasedSink.FileResult}.
     */
    @Override
    public Coder<FileResult> getWriterResultCoder() {
      return SerializableCoder.of(FileResult.class);
    }

    /**
     * Returns the FileBasedSink for this write operation.
     */
    @Override
    public FileBasedSink<T> getSink() {
      return sink;
    }
  }

  /**
   * Abstract {@link Sink.Writer} that writes a bundle to a {@link FileBasedSink}. Subclass
   * implementations provide a method that can write a single value to a {@link WritableByteChannel}
   * ({@link Sink.Writer#write}).
   *
   * <p>Subclass implementations may also override methods that write headers and footers before and
   * after the values in a bundle, respectively, as well as provide a MIME type for the output
   * channel.
   *
   * <p>Multiple FileBasedWriter instances may be created on the same worker, and therefore any
   * access to static members or methods should be thread safe.
   *
   * @param <T> the type of values to write.
   */
  public abstract static class FileBasedWriter<T> extends Writer<T, FileResult> {
    private static final Logger LOG = LoggerFactory.getLogger(FileBasedWriter.class);

    final FileBasedWriteOperation<T> writeOperation;

    /**
     * Unique id for this output bundle.
     */
    private String id;

    /**
     * The filename of the output bundle. Equal to the
     * {@link FileBasedSink.FileBasedWriteOperation#TEMPORARY_FILENAME_SEPARATOR} and id appended to
     * the baseName.
     */
    private String filename;

    /**
     * The channel to write to.
     */
    private WritableByteChannel channel;

    /**
     * The MIME type used in the creation of the output channel (if the file system supports it).
     *
     * <p>GCS, for example, supports writing files with Content-Type metadata.
     *
     * <p>May be overridden. Default is {@link MimeTypes#TEXT}. See {@link MimeTypes} for other
     * options.
     */
    protected String mimeType = MimeTypes.TEXT;

    /**
     * Construct a new FileBasedWriter with a base filename.
     */
    public FileBasedWriter(FileBasedWriteOperation<T> writeOperation) {
      Preconditions.checkNotNull(writeOperation);
      this.writeOperation = writeOperation;
    }

    /**
     * Called with the channel that a subclass will write its header, footer, and values to.
     * Subclasses should either keep a reference to the channel provided or create and keep a
     * reference to an appropriate object that they will use to write to it.
     *
     * <p>Called before any subsequent calls to writeHeader, writeFooter, and write.
     */
    protected abstract void prepareWrite(WritableByteChannel channel) throws Exception;

    /**
     * Writes header at the beginning of output files. Nothing by default; subclasses may override.
     */
    protected void writeHeader() throws Exception {}

    /**
     * Writes footer at the end of output files. Nothing by default; subclasses may override.
     */
    protected void writeFooter() throws Exception {}

    /**
     * Opens the channel.
     */
    @Override
    public final void open(String uId) throws Exception {
      this.id = uId;
      filename = FileBasedWriteOperation.buildTemporaryFilename(
          getWriteOperation().baseTemporaryFilename, uId);
      LOG.debug("Opening {}.", filename);
      channel = IOChannelUtils.create(filename, mimeType);
      try {
        prepareWrite(channel);
        LOG.debug("Writing header to {}.", filename);
        writeHeader();
      } catch (Exception e) {
        // The caller shouldn't have to close() this Writer if it fails to open(), so close the
        // channel if prepareWrite() or writeHeader() fails.
        try {
          LOG.error("Writing header to {} failed, closing channel.", filename);
          channel.close();
        } catch (IOException closeException) {
          // Log exception and mask it.
          LOG.error("Closing channel for {} failed: {}", filename, closeException.getMessage());
        }
        // Throw the exception that caused the write to fail.
        throw e;
      }
      LOG.debug("Starting write of bundle {} to {}.", this.id, filename);
    }

    /**
     * Closes the channel and return the bundle result.
     */
    @Override
    public final FileResult close() throws Exception {
      try (WritableByteChannel theChannel = channel) {
        LOG.debug("Writing footer to {}.", filename);
        writeFooter();
      }
      FileResult result = new FileResult(filename);
      LOG.debug("Result for bundle {}: {}", this.id, filename);
      return result;
    }

    /**
     * Return the FileBasedWriteOperation that this Writer belongs to.
     */
    @Override
    public FileBasedWriteOperation<T> getWriteOperation() {
      return writeOperation;
    }
  }

  /**
   * Result of a single bundle write. Contains the filename of the bundle.
   */
  public static final class FileResult implements Serializable {
    private final String filename;

    public FileResult(String filename) {
      this.filename = filename;
    }

    public String getFilename() {
      return filename;
    }
  }

  // File system operations
  // Warning: These class are purposefully private and will be replaced by more robust file I/O
  // utilities. Not for use outside FileBasedSink.

  /**
   * Factory for FileOperations.
   */
  private static class FileOperationsFactory {
    /**
     * Return a FileOperations implementation based on which IOChannel would be used to write to a
     * location specification (not necessarily a filename, as it may contain wildcards).
     *
     * <p>Only supports File and GCS locations (currently, the only factories registered with
     * IOChannelUtils). For other locations, an exception is thrown.
     */
    public static FileOperations getFileOperations(String spec, PipelineOptions options)
        throws IOException {
      IOChannelFactory factory = IOChannelUtils.getFactory(spec);
      if (factory instanceof GcsIOChannelFactory) {
        return new GcsOperations(options);
      } else if (factory instanceof FileIOChannelFactory) {
        return new LocalFileOperations();
      } else {
        throw new IOException("Unrecognized file system.");
      }
    }
  }

  /**
   * Copy and Remove operations for files. Operations behave like remove-if-existing and
   * copy-if-existing and do not throw exceptions on file not found to enable retries of these
   * operations in the case of transient error.
   */
  private static interface FileOperations {
    /**
     * Copy a collection of files from one location to another.
     *
     * <p>The number of source filenames must equal the number of destination filenames.
     *
     * @param srcFilenames the source filenames.
     * @param destFilenames the destination filenames.
     */
    public void copy(List<String> srcFilenames, List<String> destFilenames) throws IOException;

    /**
     * Remove a collection of files.
     */
    public void remove(Collection<String> filenames) throws IOException;
  }

  /**
   * GCS file system operations.
   */
  private static class GcsOperations implements FileOperations {
    private static final Logger LOG = LoggerFactory.getLogger(GcsOperations.class);

    /**
     * Maximum number of requests permitted in a GCS batch request.
     */
    private static final int MAX_REQUESTS_PER_BATCH = 1000;

    private ApiErrorExtractor errorExtractor = new ApiErrorExtractor();
    private GcsOptions gcsOptions;
    private Storage gcs;
    private BatchHelper batchHelper;

    public GcsOperations(PipelineOptions options) {
      gcsOptions = options.as(GcsOptions.class);
      gcs = Transport.newStorageClient(gcsOptions).build();
      batchHelper =
          new BatchHelper(gcs.getRequestFactory().getInitializer(), gcs, MAX_REQUESTS_PER_BATCH);
    }

    @Override
    public void copy(List<String> srcFilenames, List<String> destFilenames) throws IOException {
      Preconditions.checkArgument(
          srcFilenames.size() == destFilenames.size(),
          String.format("Number of source files {} must equal number of destination files {}",
              srcFilenames.size(), destFilenames.size()));
      for (int i = 0; i < srcFilenames.size(); i++) {
        final GcsPath sourcePath = GcsPath.fromUri(srcFilenames.get(i));
        final GcsPath destPath = GcsPath.fromUri(destFilenames.get(i));
        LOG.debug("Copying {} to {}", sourcePath, destPath);
        Storage.Objects.Copy copyObject = gcs.objects().copy(sourcePath.getBucket(),
            sourcePath.getObject(), destPath.getBucket(), destPath.getObject(), null);
        batchHelper.queue(copyObject, new JsonBatchCallback<StorageObject>() {
          @Override
          public void onSuccess(StorageObject obj, HttpHeaders responseHeaders) {
            LOG.debug("Successfully copied {} to {}", sourcePath, destPath);
          }

          @Override
          public void onFailure(GoogleJsonError e, HttpHeaders responseHeaders) throws IOException {
            // Do nothing on item not found.
            if (!errorExtractor.itemNotFound(e)) {
              throw new IOException(e.toString());
            }
            LOG.debug("{} does not exist.", sourcePath);
          }
        });
      }
      batchHelper.flush();
    }

    @Override
    public void remove(Collection<String> filenames) throws IOException {
      for (String filename : filenames) {
        final GcsPath path = GcsPath.fromUri(filename);
        LOG.debug("Removing: " + path);
        Storage.Objects.Delete deleteObject =
            gcs.objects().delete(path.getBucket(), path.getObject());
        batchHelper.queue(deleteObject, new JsonBatchCallback<Void>() {
          @Override
          public void onSuccess(Void obj, HttpHeaders responseHeaders) throws IOException {
            LOG.debug("Successfully removed {}", path);
          }

          @Override
          public void onFailure(GoogleJsonError e, HttpHeaders responseHeaders) throws IOException {
            // Do nothing on item not found.
            if (!errorExtractor.itemNotFound(e)) {
              throw new IOException(e.toString());
            }
            LOG.debug("{} does not exist.", path);
          }
        });
      }
      batchHelper.flush();
    }
  }

  /**
   * File systems supported by {@link Files}.
   */
  private static class LocalFileOperations implements FileOperations {
    private static final Logger LOG = LoggerFactory.getLogger(LocalFileOperations.class);

    @Override
    public void copy(List<String> srcFilenames, List<String> destFilenames) throws IOException {
      Preconditions.checkArgument(
          srcFilenames.size() == destFilenames.size(),
          String.format("Number of source files {} must equal number of destination files {}",
              srcFilenames.size(), destFilenames.size()));
      int numFiles = srcFilenames.size();
      for (int i = 0; i < numFiles; i++) {
        String src = srcFilenames.get(i);
        String dst = destFilenames.get(i);
        LOG.debug("Copying {} to {}", src, dst);
        copyOne(src, dst);
      }
    }

    private void copyOne(String source, String destination) throws IOException {
      try {
        // Copy the source file, replacing the existing destination.
        Files.copy(Paths.get(source), Paths.get(destination), StandardCopyOption.REPLACE_EXISTING);
      } catch (NoSuchFileException e) {
        LOG.debug("{} does not exist.", source);
        // Suppress exception if file does not exist.
      }
    }

    @Override
    public void remove(Collection<String> filenames) throws IOException {
      for (String filename : filenames) {
        LOG.debug("Removing file {}", filename);
        removeOne(filename);
      }
    }

    private void removeOne(String filename) throws IOException {
      // Delete the file if it exists.
      boolean exists = Files.deleteIfExists(Paths.get(filename));
      if (!exists) {
        LOG.debug("{} does not exist.", filename);
      }
    }
  }

  /**
   * BatchHelper abstracts out the logic for the maximum requests per batch for GCS.
   *
   * <p>Copy of
   * https://github.com/GoogleCloudPlatform/bigdata-interop/blob/master/gcs/src/main/java/com/google/cloud/hadoop/gcsio/BatchHelper.java
   *
   * <p>Copied to prevent Dataflow from depending on the Hadoop-related dependencies that are not
   * used in Dataflow.  Hadoop-related dependencies will be removed from the Google Cloud Storage
   * Connector (https://cloud.google.com/hadoop/google-cloud-storage-connector) so that this project
   * and others may use the connector without introducing unnecessary dependencies.
   *
   * <p>This class is not thread-safe; create a new BatchHelper instance per single-threaded logical
   * grouping of requests.
   */
  @NotThreadSafe
  private static class BatchHelper {
    /**
     * Callback that causes a single StorageRequest to be added to the BatchRequest.
     */
    protected static interface QueueRequestCallback {
      void enqueue() throws IOException;
    }

    private final List<QueueRequestCallback> pendingBatchEntries;
    private final BatchRequest batch;

    // Number of requests that can be queued into a single actual HTTP request
    // before a sub-batch is sent.
    private final long maxRequestsPerBatch;

    // Flag that indicates whether there is an in-progress flush.
    private boolean flushing = false;

    /**
     * Primary constructor, generally accessed only via the inner Factory class.
     */
    public BatchHelper(
        HttpRequestInitializer requestInitializer, Storage gcs, long maxRequestsPerBatch) {
      this.pendingBatchEntries = new LinkedList<>();
      this.batch = gcs.batch(requestInitializer);
      this.maxRequestsPerBatch = maxRequestsPerBatch;
    }

    /**
     * Adds an additional request to the batch, and possibly flushes the current contents of the
     * batch if {@code maxRequestsPerBatch} has been reached.
     */
    public <T> void queue(final StorageRequest<T> req, final JsonBatchCallback<T> callback)
        throws IOException {
      QueueRequestCallback queueCallback = new QueueRequestCallback() {
        @Override
        public void enqueue() throws IOException {
          req.queue(batch, callback);
        }
      };
      pendingBatchEntries.add(queueCallback);

      flushIfPossibleAndRequired();
    }

    // Flush our buffer if we have more pending entries than maxRequestsPerBatch
    private void flushIfPossibleAndRequired() throws IOException {
      if (pendingBatchEntries.size() > maxRequestsPerBatch) {
        flushIfPossible();
      }
    }

    // Flush our buffer if we are not already in a flush operation and we have data to flush.
    private void flushIfPossible() throws IOException {
      if (!flushing && pendingBatchEntries.size() > 0) {
        flushing = true;
        try {
          while (batch.size() < maxRequestsPerBatch && pendingBatchEntries.size() > 0) {
            QueueRequestCallback head = pendingBatchEntries.remove(0);
            head.enqueue();
          }

          batch.execute();
        } finally {
          flushing = false;
        }
      }
    }


    /**
     * Sends any currently remaining requests in the batch; should be called at the end of any
     * series of batched requests to ensure everything has been sent.
     */
    public void flush() throws IOException {
      flushIfPossible();
    }
  }

  static class ReshardForWrite<T> extends PTransform<PCollection<T>, PCollection<T>> {
    @Override
    public PCollection<T> apply(PCollection<T> input) {
      return input
          // TODO: This would need to be adapted to write per-window shards.
          .apply(Window.<T>into(new GlobalWindows())
                       .triggering(DefaultTrigger.of())
                       .discardingFiredPanes())
          .apply("RandomKey", ParDo.of(
              new DoFn<T, KV<Long, T>>() {
                transient long counter, step;
                @Override
                public void startBundle(Context c) {
                  counter = (long) (Math.random() * Long.MAX_VALUE);
                  step = 1 + 2 * (long) (Math.random() * Long.MAX_VALUE);
                }
                @Override
                public void processElement(ProcessContext c) {
                  counter += step;
                  c.output(KV.of(counter, c.element()));
                }
              }))
          .apply(GroupByKey.<Long, T>create())
          .apply("Ungroup", ParDo.of(
              new DoFn<KV<Long, Iterable<T>>, T>() {
                @Override
                public void processElement(ProcessContext c) {
                  for (T item : c.element().getValue()) {
                    c.output(item);
                  }
                }
              }));
    }
  }
}
