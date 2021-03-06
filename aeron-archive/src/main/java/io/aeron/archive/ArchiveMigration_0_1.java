/*
 * Copyright 2014-2019 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.archive;

import io.aeron.archive.codecs.RecordingDescriptorDecoder;
import io.aeron.archive.codecs.RecordingDescriptorEncoder;
import io.aeron.archive.codecs.RecordingDescriptorHeaderDecoder;
import io.aeron.archive.codecs.RecordingDescriptorHeaderEncoder;
import io.aeron.logbuffer.LogBufferDescriptor;
import org.agrona.AsciiEncoding;
import org.agrona.CloseHelper;
import org.agrona.LangUtil;
import org.agrona.SemanticVersion;
import org.agrona.collections.ArrayUtil;

import java.io.File;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static io.aeron.archive.Catalog.INVALID;
import static io.aeron.archive.MigrationUtils.fullVersionString;

class ArchiveMigration_0_1 implements ArchiveMigrationStep
{
    private static final int MINIMUM_VERSION = SemanticVersion.compose(1, 0, 0);

    public int minimumVersion()
    {
        return MINIMUM_VERSION;
    }

    public void migrate(final ArchiveMarkFile markFile, final Catalog catalog, final File archiveDir)
    {
        final FileChannel migrationTimestampFile = MigrationUtils.createMigrationTimestampFile(
            archiveDir, markFile.decoder().version(), minimumVersion());

        catalog.forEach(
            (headerEncoder, headerDecoder, encoder, decoder) ->
            {
                final String version0Prefix = decoder.recordingId() + "-";
                final String version0Suffix = ".rec";
                String[] segmentFiles = archiveDir.list(
                    (dir, filename) -> filename.startsWith(version0Prefix) && filename.endsWith(version0Suffix));

                if (null == segmentFiles)
                {
                    segmentFiles = ArrayUtil.EMPTY_STRING_ARRAY;
                }

                migrateRecording(
                    archiveDir,
                    segmentFiles,
                    version0Prefix,
                    version0Suffix,
                    headerEncoder,
                    headerDecoder,
                    encoder,
                    decoder);
            });

        markFile.encoder().version(minimumVersion());
        catalog.updateVersion(minimumVersion());

        CloseHelper.close(migrationTimestampFile);
    }

    public void migrateRecording(
        final File archiveDir,
        final String[] segmentFiles,
        final String prefix,
        final String suffix,
        final RecordingDescriptorHeaderEncoder headerEncoder,
        final RecordingDescriptorHeaderDecoder headerDecoder,
        final RecordingDescriptorEncoder encoder,
        final RecordingDescriptorDecoder decoder)
    {
        final long recordingId = decoder.recordingId();
        final long startPosition = decoder.startPosition();
        final long segmentLength = decoder.segmentFileLength();
        final long segmentBasePosition = startPosition - (startPosition & (segmentLength - 1));
        final int positionBitsToShift = LogBufferDescriptor.positionBitsToShift((int)segmentLength);

        if (headerDecoder.valid() == INVALID)
        {
            return;
        }

        System.out.println(
            "(recordingId=" + recordingId + ") segmentBasePosition=" + segmentBasePosition + " " +
            "segmentLength=" + segmentLength + "(" + positionBitsToShift + ")");

        for (final String filename : segmentFiles)
        {
            final int length = filename.length();
            final int offset = prefix.length();
            final int remaining = length - offset - suffix.length();
            final long segmentIndex;

            if (remaining > 0)
            {
                try
                {
                    segmentIndex = AsciiEncoding.parseIntAscii(filename, offset, remaining);
                }
                catch (final Exception ex)
                {
                    System.err.println(
                        "(recordingId=" + recordingId + ") ERR: malformed recording filename:" + filename);
                    throw ex;
                }

                final long segmentPosition = (segmentIndex << positionBitsToShift) + segmentBasePosition;
                final String newFilename = prefix + segmentPosition + suffix;

                final Path sourcePath = new File(archiveDir, filename).toPath();
                final Path targetPath = sourcePath.resolveSibling(newFilename);

                System.out.println("(recordingId=" + recordingId + ") renaming " + sourcePath + " -> " + targetPath);

                try
                {
                    Files.move(sourcePath, targetPath);
                    Files.setLastModifiedTime(targetPath, FileTime.fromMillis(System.currentTimeMillis()));
                }
                catch (final Exception ex)
                {
                    System.err.println(
                        "(recordingId=" + recordingId + ") ERR: could not rename filename: " +
                        sourcePath + " -> " + targetPath);
                    LangUtil.rethrowUnchecked(ex);
                }
            }
        }

        System.out.println("(recordingId=" + recordingId + ") OK");
    }

    public String toString()
    {
        return "to " + fullVersionString(minimumVersion());
    }
}
