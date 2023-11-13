/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.cli.fs.command;

import alluxio.cli.Command;
import alluxio.client.file.FileSystemContext;
import alluxio.client.file.cache.CacheManager;
import alluxio.client.file.cache.CacheManagerOptions;
import alluxio.client.file.cache.PageId;
import alluxio.client.file.cache.PageMetaStore;
import alluxio.client.file.cache.store.PageStoreType;
import alluxio.conf.Configuration;
import alluxio.conf.InstancedConfiguration;
import alluxio.conf.PropertyKey;
import alluxio.exception.AlluxioException;
import alluxio.exception.status.InvalidArgumentException;
import alluxio.util.FormatUtils;
import alluxio.util.io.FileUtils;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.util.Map;
import javax.annotation.Nullable;

public class PageStoreGenerateFileCommand extends AbstractFileSystemCommand {
  private static final Option FILE_ID_TEMPLATE_OPTION =
      Option.builder()
          .longOpt("file-id-template")
          .required(false)
          .hasArg(true)
          .numberOfArgs(1)
          .desc("Template for the file IDs generated. Must contain one '%s'.")
          .build();
  private static final Option PAGE_STORE_DIR_OPTION =
      Option.builder()
          .longOpt("page-store-dir")
          .required(true)
          .hasArg(true)
          .numberOfArgs(1)
          .desc("root path of the page store")
          .build();
  private static final Option PAGE_SIZE_OPTION =
      Option.builder()
          .longOpt("page-size")
          .required(false)
          .hasArg(true)
          .numberOfArgs(1)
          .desc("size of the pages generated")
          .build();

  private static final Option NUM_FILES_OPTION =
      Option.builder()
          .longOpt("num-files")
          .required(true)
          .hasArg(true)
          .numberOfArgs(1)
          .desc("number of files generated")
          .build();

  private static final Option NUM_PAGES_PER_FILE_OPTION =
      Option.builder()
          .longOpt("num-pages-per-file")
          .required(true)
          .hasArg(true)
          .numberOfArgs(1)
          .desc("number of pages per file generated")
          .build();

  private static final Option ASYNC_RESTORE_OPTION =
      Option.builder()
          .longOpt("async-restore")
          .required(false)
          .desc("whether restoring the metastore asynchronously")
          .build();

  private static final Option CLEAR_OPTION =
      Option.builder()
          .longOpt("clear")
          .required(false)
          .desc("whether to clear the path of the page store before generating files")
          .build();

  public PageStoreGenerateFileCommand(
      @Nullable FileSystemContext fsContext) {
    super(fsContext);
  }

  @Override
  public String getCommandName() {
    return "pageStoreGenerateFile";
  }

  @Override
  public Options getOptions() {
    return new Options()
        .addOption(ASYNC_RESTORE_OPTION)
        .addOption(CLEAR_OPTION)
        .addOption(FILE_ID_TEMPLATE_OPTION)
        .addOption(PAGE_SIZE_OPTION)
        .addOption(NUM_FILES_OPTION)
        .addOption(NUM_PAGES_PER_FILE_OPTION)
        .addOption(PAGE_STORE_DIR_OPTION);
  }

  @Override
  public boolean hasSubCommand() {
    return super.hasSubCommand();
  }

  @Override
  public Map<String, Command> getSubCommands() {
    return super.getSubCommands();
  }

  @Override
  public CommandLine parseAndValidateArgs(String... args) throws InvalidArgumentException {
    return super.parseAndValidateArgs(args);
  }

  @Override
  public void validateArgs(CommandLine cl) throws InvalidArgumentException {
    super.validateArgs(cl);
    String pageStorePath = cl.getOptionValue(PAGE_STORE_DIR_OPTION.getLongOpt());
    if (pageStorePath != null) {
      try {
        Paths.get(pageStorePath);
      } catch (InvalidPathException e) {
        throw new InvalidArgumentException(e);
      }
    }
    String fileIdTemplate = cl.getOptionValue(FILE_ID_TEMPLATE_OPTION.getLongOpt());
    if (fileIdTemplate != null) {
      Preconditions.checkArgument(StringUtils.countMatches(fileIdTemplate, "%s") == 1,
          "file ID template must contain exactly one occurrence of '%s'");
    }
  }

  @Override
  public int run(CommandLine cl) throws AlluxioException, IOException {
    String pageStorePath = cl.getOptionValue(PAGE_STORE_DIR_OPTION.getLongOpt(), "/tmp"
        + "/alluxio_cache");
    int numFiles = Integer.parseInt(cl.getOptionValue(NUM_FILES_OPTION.getLongOpt(), "10"));
    int numPagesPerFile =
        Integer.parseInt(cl.getOptionValue(NUM_PAGES_PER_FILE_OPTION.getLongOpt(), "10"));
    long pageSize = FormatUtils.parseSpaceSize(cl.getOptionValue(PAGE_SIZE_OPTION.getLongOpt(),
        "4KB"));
    boolean asyncRestoreEnabled = cl.hasOption(ASYNC_RESTORE_OPTION.getLongOpt());
    boolean clear = cl.hasOption(CLEAR_OPTION.getLongOpt());
    if (clear) {
      System.out.printf("Clearing path %s%n", pageStorePath);
      FileUtils.deletePathRecursively(pageStorePath);
    }
    String fileIdTemplate =
        cl.getOptionValue(FILE_ID_TEMPLATE_OPTION.getLongOpt(), "file%s");

    InstancedConfiguration conf = Configuration.modifiableGlobal();
    conf.set(PropertyKey.WORKER_PAGE_STORE_TYPE, PageStoreType.LOCAL);
    conf.set(PropertyKey.WORKER_PAGE_STORE_PAGE_SIZE, pageSize);
    conf.set(PropertyKey.WORKER_PAGE_STORE_DIRS, ImmutableList.of(pageStorePath));
    conf.set(PropertyKey.WORKER_PAGE_STORE_ASYNC_RESTORE_ENABLED, asyncRestoreEnabled);
    CacheManagerOptions cacheManagerOptions = CacheManagerOptions.createForWorker(conf);
    PageMetaStore metaStore = PageMetaStore.create(cacheManagerOptions);
    byte[] pageData = new byte[(int) pageSize];

    try (CacheManager cacheManager = CacheManager.Factory.create(conf, cacheManagerOptions,
        metaStore);) {
      for (int fileIndex = 0; fileIndex < numFiles; fileIndex++) {
        String fileId = String.format(fileIdTemplate, fileIndex);
        for (int pageIndex = 0; pageIndex < numPagesPerFile; pageIndex++) {
          PageId pageId = new PageId(fileId, pageIndex);
          cacheManager.put(pageId, pageData);
        }
      }
    } catch (Exception e) {
      e.printStackTrace(System.err);
      return 1;
    }
    return 0;
  }

  @Override
  public String getUsage() {
    return "Generates files and write them into the page store to the location given by "
        + PAGE_SIZE_OPTION.getLongOpt() + ".";
  }

  @Override
  public String getDescription() {
    return "test page store memory consumption";
  }

  @Override
  public void close() throws IOException {
    super.close();
  }
}
