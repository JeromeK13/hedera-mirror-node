package com.hedera.mirror.importer.parser.record;

/*-
 * ‌
 * Hedera Mirror Node
 * ​
 * Copyright (C) 2019 - 2020 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

import com.google.common.base.Stopwatch;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.inject.Named;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.codec.binary.Hex;
import org.springframework.scheduling.annotation.Scheduled;

import com.hedera.mirror.importer.domain.ApplicationStatusCode;
import com.hedera.mirror.importer.domain.RecordFile;
import com.hedera.mirror.importer.domain.TransactionTypeEnum;
import com.hedera.mirror.importer.exception.DuplicateFileException;
import com.hedera.mirror.importer.exception.ParserException;
import com.hedera.mirror.importer.parser.FileParser;
import com.hedera.mirror.importer.parser.domain.RecordItem;
import com.hedera.mirror.importer.parser.domain.StreamFileData;
import com.hedera.mirror.importer.repository.ApplicationStatusRepository;
import com.hedera.mirror.importer.util.FileDelimiter;
import com.hedera.mirror.importer.util.ShutdownHelper;
import com.hedera.mirror.importer.util.Utility;

/**
 * This is a utility file to read back service record file generated by Hedera node
 */
@Log4j2
@Named
@ConditionalOnRecordParser
public class RecordFileParser implements FileParser {

    private final ApplicationStatusRepository applicationStatusRepository;
    private final RecordParserProperties parserProperties;
    private final MeterRegistry meterRegistry;
    private final RecordItemListener recordItemListener;
    private final RecordStreamFileListener recordStreamFileListener;

    // Metrics
    private final Timer.Builder parseDurationMetric;
    private final Timer.Builder transactionLatencyMetric;
    private final DistributionSummary.Builder transactionSizeMetric;

    public RecordFileParser(ApplicationStatusRepository applicationStatusRepository,
                            RecordParserProperties parserProperties, MeterRegistry meterRegistry,
                            RecordItemListener recordItemListener,
                            RecordStreamFileListener recordStreamFileListener) {
        this.applicationStatusRepository = applicationStatusRepository;
        this.parserProperties = parserProperties;
        this.meterRegistry = meterRegistry;
        this.recordItemListener = recordItemListener;
        this.recordStreamFileListener = recordStreamFileListener;

        parseDurationMetric = Timer.builder("hedera.mirror.parse.duration")
                .description("The duration in seconds it took to parse the file and store it in the database")
                .tag("type", parserProperties.getStreamType().toString());

        transactionSizeMetric = DistributionSummary.builder("hedera.mirror.transaction.size")
                .description("The size of the transaction in bytes")
                .baseUnit("bytes");

        transactionLatencyMetric = Timer.builder("hedera.mirror.transaction.latency")
                .description("The difference in ms between the time consensus was achieved and the mirror node " +
                        "processed the transaction");
    }

    /**
     * Given a service record name, read its prevFileHash
     *
     * @param fileName the name of record file to read
     * @return return previous file hash's Hex String
     */
    public static String readPrevFileHash(String fileName) {
        File file = new File(fileName);
        if (file.exists() == false) {
            log.warn("File does not exist {}", fileName);
            return null;
        }
        byte[] prevFileHash = new byte[48];
        try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
            // record_format_version
            dis.readInt();
            // version
            dis.readInt();

            byte typeDelimiter = dis.readByte();

            if (typeDelimiter == FileDelimiter.RECORD_TYPE_PREV_HASH) {
                dis.read(prevFileHash);
                String hexString = Hex.encodeHexString(prevFileHash);
                log.trace("Read previous file hash {} for file {}", hexString, fileName);
                return hexString;
            } else {
                log.error("Expecting previous file hash, but found file delimiter {} for file {}", typeDelimiter,
                        fileName);
            }
        } catch (Exception e) {
            log.error("Error reading previous file hash {}", fileName, e);
        }

        return null;
    }

    /**
     * Given a service record name, read and parse and return as a list of service record pair
     *
     * @param streamFileData containing information about file to be processed
     */
    public void loadRecordFile(StreamFileData streamFileData) throws IOException {
        Stopwatch stopwatch = Stopwatch.createStarted();
        long loadStart = Instant.now().getEpochSecond();
        recordStreamFileListener.onStart(streamFileData);
        String fileName = streamFileData.getFilename();
        String actualPrevFileHash = "";
        long counter = 0;
        Integer recordFileVersion = 0;
        Boolean success = false;

        try (DataInputStream dis = new DataInputStream(streamFileData.getInputStream())) {
            recordFileVersion = dis.readInt();
            int version = dis.readInt();
            log.info("Loading version {} record file: {}", recordFileVersion, fileName);
            while (dis.available() != 0) {
                byte typeDelimiter = dis.readByte();

                switch (typeDelimiter) {
                    case FileDelimiter.RECORD_TYPE_PREV_HASH:
                        byte[] readFileHash = new byte[48];
                        dis.read(readFileHash);
                        actualPrevFileHash = Hex.encodeHexString(readFileHash);
                        String expectedPrevFileHash = applicationStatusRepository.findByStatusCode(
                                ApplicationStatusCode.LAST_PROCESSED_RECORD_HASH);
                        if (Utility.hashIsEmpty(expectedPrevFileHash)) {
                            log.error("Previous file hash not available");
                            expectedPrevFileHash = actualPrevFileHash;
                        }
                        log.trace("actual file hash = {}, expected file hash = {}", actualPrevFileHash,
                                expectedPrevFileHash);
                        if (!actualPrevFileHash.contentEquals(expectedPrevFileHash)) {
                            if (applicationStatusRepository
                                    .findByStatusCode(ApplicationStatusCode.RECORD_HASH_MISMATCH_BYPASS_UNTIL_AFTER)
                                    .compareTo(Utility.getFileName(fileName)) < 0) {
                                // last file for which mismatch is allowed is in the past
                                throw new ParserException(String.format(
                                        "Hash mismatch for file %s. Expected = %s, Actual = %s",
                                        fileName, expectedPrevFileHash, actualPrevFileHash));
                            }
                        }
                        break;
                    case FileDelimiter.RECORD_TYPE_RECORD:
                        counter++;

                        int byteLength = dis.readInt();
                        byte[] transactionRawBytes = new byte[byteLength];
                        dis.readFully(transactionRawBytes);

                        byteLength = dis.readInt();
                        byte[] recordRawBytes = new byte[byteLength];
                        dis.readFully(recordRawBytes);
                        RecordItem recordItem = new RecordItem(transactionRawBytes, recordRawBytes);

                        if (log.isTraceEnabled()) {
                            log.trace("Transaction = {}, Record = {}",
                                    Utility.printProtoMessage(recordItem.getTransaction()),
                                    Utility.printProtoMessage(recordItem.getRecord()));
                        } else {
                            log.debug("Storing transaction with consensus timestamp {}", () ->
                                    Utility.printProtoMessage(recordItem.getRecord().getConsensusTimestamp()));
                        }
                        recordItemListener.onItem(recordItem);

                        String type = TransactionTypeEnum.of(recordItem.getTransactionType()).toString();
                        transactionSizeMetric.tag("type", type)
                                .register(meterRegistry)
                                .record(transactionRawBytes.length);

                        Instant consensusTimestamp = Utility.convertToInstant(
                                recordItem.getRecord().getConsensusTimestamp());
                        transactionLatencyMetric.tag("type", type)
                                .register(meterRegistry)
                                .record(Duration.between(consensusTimestamp, Instant.now()));
                        break;
                    case FileDelimiter.RECORD_TYPE_SIGNATURE:
                        int sigLength = dis.readInt();
                        byte[] sigBytes = new byte[sigLength];
                        dis.readFully(sigBytes);
                        log.trace("File {} has signature {}", fileName, Hex.encodeHexString(sigBytes));
                        break;

                    default:
                        throw new ParserException(String.format(
                                "Unknown record file delimiter %s for file %s", typeDelimiter, fileName));
                }
            }
            String thisFileHash = Hex.encodeHexString(Utility.getRecordFileHash(fileName));
            log.trace("Calculated file hash for the current file {}", thisFileHash);
            recordStreamFileListener.onEnd(new RecordFile(null, fileName, loadStart, Instant.now().getEpochSecond(),
                    thisFileHash, actualPrevFileHash));

            if (!Utility.hashIsEmpty(thisFileHash)) {
                applicationStatusRepository
                        .updateStatusValue(ApplicationStatusCode.LAST_PROCESSED_RECORD_HASH, thisFileHash);
            }
            success = true;
        } finally {
            var elapsed = stopwatch.elapsed(TimeUnit.MILLISECONDS);
            var rate = elapsed > 0 ? (int) (1000.0 * counter / elapsed) : 0;
            log.info("Finished parsing {} transactions from record file {} in {} ({}/s)",
                    counter, fileName, stopwatch, rate);
            parseDurationMetric.tag("success", success.toString())
                    .tag("version", recordFileVersion.toString())
                    .register(meterRegistry)
                    .record(stopwatch.elapsed());
        }
    }

    /**
     * read and parse a list of record files
     *
     * @throws Exception
     */
    private void loadRecordFiles(List<String> fileNames) {
        Collections.sort(fileNames);
        for (String name : fileNames) {
            if (ShutdownHelper.isStopping()) {
                return;
            }
            InputStream fileInputStream;
            try {
                fileInputStream = new FileInputStream(new File(name));
            } catch (FileNotFoundException e) {
                log.warn("File does not exist {}", name);
                return;
            }
            try {
                loadRecordFile(new StreamFileData(name, fileInputStream));
                Utility.moveOrDeleteParsedFile(name, parserProperties);
            } catch (Exception e) {
                log.error("Error parsing file {}", name, e);
                recordStreamFileListener.onError();
                if (!(e instanceof DuplicateFileException)) { // if DuplicateFileException, continue with other files
                    return;
                }
            }
        }
    }

    @Override
    @Scheduled(fixedRateString = "${hedera.mirror.importer.parser.record.frequency:500}")
    public void parse() {
        if (ShutdownHelper.isStopping()) {
            return;
        }
        Path path = parserProperties.getValidPath();
        log.debug("Parsing record files from {}", path);
        try {
            File file = path.toFile();
            if (file.isDirectory()) { //if it's a directory

                String[] files = file.list(); // get all files under the directory
                Arrays.sort(files);           // sorted by name (timestamp)

                // add directory prefix to get full path
                List<String> fullPaths = Arrays.asList(files).stream()
                        .map(s -> file + "/" + s)
                        .collect(Collectors.toList());

                if (fullPaths != null && fullPaths.size() != 0) {
                    log.trace("Processing record files: {}", fullPaths);
                    loadRecordFiles(fullPaths);
                } else {
                    log.debug("No files to parse");
                }
            } else {
                log.error("Input parameter is not a folder: {}", path);
            }
        } catch (Exception e) {
            log.error("Error parsing files", e);
        }
    }
}
