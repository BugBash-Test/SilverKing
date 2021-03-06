package com.ms.silverking.cloud.dht.daemon.storage;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel.MapMode;

import com.ms.silverking.cloud.dht.NamespaceOptions;
import com.ms.silverking.cloud.dht.collection.IntArrayDHTKeyCuckoo;
import com.ms.silverking.cloud.dht.common.SystemTimeUtil;
import com.ms.silverking.io.util.BufferUtil;
import com.ms.silverking.log.Log;
import com.ms.silverking.numeric.NumConversion;
import com.ms.silverking.util.PropertiesHelper;

class RAMSegment extends WritableSegmentBase {
  private long segmentCreationTime;

  private static final boolean  allocateDirect;
  private static final boolean  allocateDirectDefault = true;
  private static final String   allocateDirectProperty = RAMSegment.class.getPackage().getName()
      +"."+ RAMSegment.class.getSimpleName() +".AllocateDirect";

  static {
    allocateDirect = PropertiesHelper.systemHelper.getBoolean(allocateDirectProperty, allocateDirectDefault);
    Log.warningf("%s: %s", allocateDirectProperty, allocateDirect);
  }

  static final RAMSegment create(File nsDir, int segmentNumber, int dataSegmentSize, NamespaceOptions nsOptions) {
    RandomAccessFile raFile;
    byte[] header;
    ByteBuffer dataBuf;
    int indexOffset;

    indexOffset = dataSegmentSize;
    header = SegmentFormat.newHeader(segmentNumber, dataOffset, indexOffset);

    if (allocateDirect) {
      try {
    dataBuf = ByteBuffer.allocateDirect(dataSegmentSize);
      } catch (OutOfMemoryError oome) {
        Log.logErrorWarning(oome, "Unable to allocate direct buffer for RAMSegment. Trying on-heap");
        dataBuf = ByteBuffer.allocate(dataSegmentSize);
      }
    } else {
      dataBuf = ByteBuffer.allocate(dataSegmentSize);
    }
    dataBuf.put(header);
    return new RAMSegment(nsDir, segmentNumber, dataBuf, dataSegmentSize, nsOptions);
  }

  private static File fileForSegment(File nsDir, int segmentNumber) {
    return new File(nsDir, Integer.toString(segmentNumber));
  }

  // called from create
  private RAMSegment(File nsDir, int segmentNumber, ByteBuffer dataBuf, int dataSegmentSize,
      NamespaceOptions nsOptions) {
    super(nsDir, segmentNumber, dataBuf, StoreConfiguration.ramInitialCuckooConfig, dataSegmentSize, nsOptions);
    this.segmentCreationTime = SystemTimeUtil.skSystemTimeSource.absTimeMillis();
  }

  @Override
  public void persist() throws IOException {
    ByteBuffer htBuf;
    byte[] ht;
    int offsetStoreSize;
    int htBufSize;
    long mapSize;

    offsetStoreSize = ((RAMOffsetListStore) offsetListStore).persistedSizeBytes();

    RandomAccessFile raFile;

    raFile = new RandomAccessFile(fileForSegment(nsDir, segmentNumber), "rw");
    try {
      raFile.write(dataBuf.array());

      ht = ((IntArrayDHTKeyCuckoo) keyToOffset).getAsBytes();
      htBufSize = ht.length;
      mapSize = NumConversion.BYTES_PER_INT + htBufSize + offsetStoreSize;
      htBuf = raFile.getChannel().map(MapMode.READ_WRITE, dataSegmentSize, mapSize).order(ByteOrder.nativeOrder());
      htBuf.putInt(htBufSize);
      //System.out.printf("\tpersist htBufSize: %d\tmapSize: %d\n", htBufSize, mapSize);
      htBuf.put(ht);
      htBuf.position(NumConversion.BYTES_PER_INT + htBufSize);
      ((RAMOffsetListStore) offsetListStore).persist(
          BufferUtil.sliceAt(htBuf, NumConversion.BYTES_PER_INT + htBufSize));
      //((sun.nio.ch.DirectBuffer)htBuf).cleaner().clean();
    } finally {
      raFile.close();
    }
    close();
  }

  @Override
  long getSegmentCreationMillis() {
    return segmentCreationTime;
  }

  public void close() {
  }

  public SegmentCompactionResult compact() {
    throw new RuntimeException("Compaction not supported for RAMSegment");
  }
}
