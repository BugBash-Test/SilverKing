package com.ms.silverking.cloud.dht.daemon.storage;

import java.nio.ByteBuffer;
import java.util.Date;
import java.util.Set;
import java.util.logging.Level;

import com.ms.silverking.cloud.dht.ValueCreator;
import com.ms.silverking.cloud.dht.VersionConstraint;
import com.ms.silverking.cloud.dht.common.DHTKey;
import com.ms.silverking.cloud.dht.common.InternalRetrievalOptions;
import com.ms.silverking.cloud.dht.common.MetaDataUtil;
import com.ms.silverking.collection.cuckoo.IntCuckooConstants;
import com.ms.silverking.log.Log;

abstract class AbstractSegment implements ReadableSegment {
  protected ByteBuffer dataBuf;
  protected final OffsetListStore offsetListStore;
  protected final Set<Integer> invalidatedOffsets;

  protected static final int noSuchKey = IntCuckooConstants.noSuchValue;
  protected static final boolean debugRetrieve = false;
  protected static final boolean debugPut = false;
  protected static final boolean debugExternalStore = false;

  AbstractSegment(ByteBuffer dataBuf, OffsetListStore offsetListStore, Set<Integer> invalidatedOffsets) {
    this.dataBuf = dataBuf;
    this.offsetListStore = offsetListStore;
    this.invalidatedOffsets = invalidatedOffsets;
  }

  abstract long getSegmentCreationMillis();

  abstract int getSegmentNumber();

  protected abstract int getRawOffset(DHTKey key);

  byte[] getChecksum(int offset) {
    assert offset >= 0;
    return MetaDataUtil.getChecksum(dataBuf, offset + DHTKey.BYTES_PER_KEY);
  }

  /**
   * Returns whether the operation at the particular offset is an invalidation
   */
  boolean isInvalidation(int offset) {
    if (invalidatedOffsets != null) {
      return invalidatedOffsets.contains(offset);
    } else {
      return MetaDataUtil.isInvalidation(dataBuf, offset + DHTKey.BYTES_PER_KEY);
    }
  }

  long getVersion(int offset) {
    assert offset >= 0;
    return MetaDataUtil.getVersion(dataBuf, offset + DHTKey.BYTES_PER_KEY);
  }

  long getCreationTime(int offset) {
    assert offset >= 0;
    return MetaDataUtil.getCreationTime(dataBuf, offset + DHTKey.BYTES_PER_KEY);
  }

  ValueCreator getCreator(int offset) {
    return MetaDataUtil.getCreator(dataBuf, offset + DHTKey.BYTES_PER_KEY);
  }

  int getStoredLength(int offset) {
    return MetaDataUtil.getStoredLength(dataBuf, offset + DHTKey.BYTES_PER_KEY);
  }

  private int checkOffset(int offset) {
    if (offset < 0) {
      OffsetList offsetList;

      // negative offsets are an index to the list in the offsetListStore
      try {
        offsetList = offsetListStore.getOffsetList(-offset);
      } catch (InvalidOffsetListIndexException ie) {
        throw ie;
      } catch (RuntimeException re) {
        Log.warningf("offset %d", offset);
        throw re;
      }
      // keys for all entries must be identical, so simply pick the first
      // offset to use for checking
      try {
        offset = offsetList.getFirstOffset();
      } catch (RuntimeException re) {
        Log.warningf("offset %d", offset);
        throw re;
      }
      //Log.warning("entryMatches translated offset: ", offset);
    }
    return offset;
  }

    /*
    public ByteBuffer[] retrieve(DHTKey[] keys, InternalRetrievalOptions options) {
        KeyAndInteger[] _keys;
        ByteBuffer[]    buffers;
        
        _keys = new KeyAndInteger[keys.length];
        for (int i = 0; i < _keys.length; i++) {
            DHTKey  k;
            
            k = keys[i];
                                            // this is just for request ordering, so we only need the probable offset
            _keys[i] = new KeyAndInteger(k, getProbableOffset(k, options.getVersionConstraint()));
        }
        Arrays.sort(_keys, KeyAndInteger.getIntegerComparator());
        buffers = new ByteBuffer[_keys.length];
        for (int i = 0; i < _keys.length; i++) {
            buffers[i] = retrieve(_keys[i], options, _keys[i].getInteger()  the offset );
        }
        return buffers;
    }
    */

  public ByteBuffer retrieve(DHTKey key, InternalRetrievalOptions options) {
    return retrieve(key, options, false);
  }

  public ByteBuffer retrieve(DHTKey key, InternalRetrievalOptions options, boolean verifySS) {
    try {
      int offset;

      offset = getRawOffset(key);
      if (debugRetrieve) {
        Log.warningf("AbstractSegment.retrieve %s %s %d", key, options, offset);
      }
      return retrieve(key, options, offset, verifySS);
    } catch (RuntimeException re) { // FIXME - TEMP DEBUG
      Log.warningf("segment %d %s %s", getSegmentNumber(), key, options);
      re.printStackTrace();
      throw re;
    }
  }

  /**
   * For utility use only
   *
   * @param key
   * @param offset
   * @return
   */
  public ByteBuffer retrieveForDebug(DHTKey key, int offset) {
    int storedLength;
    ByteBuffer buffer;
    ByteBuffer returnBuffer;

    offset += DHTKey.BYTES_PER_KEY;
    storedLength = MetaDataUtil.getStoredLength(dataBuf, offset);
    buffer = dataBuf.asReadOnlyBuffer();
    buffer.position(offset);
    buffer.limit(offset + storedLength);
    returnBuffer = buffer.slice();
    return returnBuffer;
  }

  @SuppressWarnings("unused")
  private ByteBuffer retrieve(DHTKey key, InternalRetrievalOptions options, int offset, boolean verifySS) {
    // FUTURE - think about getResolvedOffset and doubleCheckVersion
    // Currently can't use getResolvedOffset due to the doubleCheckVersion
    // code. Need to determine if that code is required.
    if (debugRetrieve || Log.levelMet(Level.FINE)) {
      Log.warning("segment number: " + getSegmentNumber());
      Log.warning("key offset: ", key + " " + offset);
    }
    if (offset == noSuchKey) {
      if (debugRetrieve) {
        Log.warning("noSuchKey");
      }
      return null;
    } else {
      int storedLength;
      ByteBuffer buffer;
      boolean doubleCheckVersion;
      ByteBuffer returnBuffer;

      if (offset < 0) {
        OffsetList offsetList;
        ValidityVerifier validityVerifier;

        doubleCheckVersion = false;
        //System.out.printf("offset %d -offset %d\n", offset, -offset);
        if (debugRetrieve) {
          Log.warning("Looking in offset list: " + -offset);
        }
        offsetList = offsetListStore.getOffsetList(-offset);
        if (debugRetrieve) {
          Log.warning("offsetList: ", offsetList);
          offsetList.displayForDebug();
        }
        if (verifySS && options.getVerifyStorageState()) {
          validityVerifier = new ValidityVerifier(dataBuf, options.getCPSSToVerify());
        } else {
          validityVerifier = null;
        }
        offset = offsetList.getOffset(options.getVersionConstraint(), validityVerifier);
        if (offset < 0) {
          offset = noSuchKey; // FUTURE - think about this
          if (debugRetrieve) {
            Log.warning("Couldn't find key in offset list. options: " + options.getVersionConstraint());
          }
        }
        if (debugRetrieve || Log.levelMet(Level.FINE)) {
          Log.warning("offset list offset: ", key + " " + offset);
        }
      } else {
        doubleCheckVersion = true;
      }

      if (offset < 0) {
        if (debugRetrieve) {
          Log.warning("offset < 0");
        }
        return null;
      } else {
        offset += DHTKey.BYTES_PER_KEY;
        switch (options.getRetrievalType()) {
        case VALUE:
        case VALUE_AND_META_DATA:
          // FUTURE - consider creating a new buffer type to allow creation in one operation
          storedLength = MetaDataUtil.getStoredLength(dataBuf, offset); // FUTURE - think about this
          //System.out.println("storedLength: "+ MetaDataUtil.getStoredLength(data, offset));
          //buffer = ByteBuffer.wrap(data, offset, storedLength);
          buffer = dataBuf.asReadOnlyBuffer();
          buffer.position(offset);
          buffer.limit(offset + storedLength);
          break;
        case EXISTENCE: // fall through
        case META_DATA:
          //buffer = ByteBuffer.wrap(data, offset, MetaDataUtil.getMetaDataLength(data, offset));
          buffer = dataBuf.asReadOnlyBuffer();
          buffer.position(offset);
          buffer.limit(offset + MetaDataUtil.getMetaDataLength(dataBuf, offset));
          if (MetaDataUtil.isSegmented(buffer.slice())) {
            // FUTURE THIS CODE IS COPIED FROM VALUE CASES, ELIM THE DUPLICATE CODE
            storedLength = MetaDataUtil.getStoredLength(dataBuf,
                offset); // FUTURE & verify that segmented metadatautil works
            //System.out.println("storedLength: "+ MetaDataUtil.getStoredLength(data, offset));
            //buffer = ByteBuffer.wrap(data, offset, storedLength);
            buffer = dataBuf.asReadOnlyBuffer();
            buffer.position(offset);
            buffer.limit(offset + storedLength);
          }
          break;
        default:
          throw new RuntimeException();
        }
        returnBuffer = buffer.slice();
        if (doubleCheckVersion) {
          VersionConstraint vc;

          vc = options.getVersionConstraint();

          if (debugRetrieve && returnBuffer != null) {
            boolean a;
            boolean b;
            boolean c;
            boolean d;

            a = !vc.equals(VersionConstraint.greatest);
            b = !vc.matches(MetaDataUtil.getVersion(returnBuffer, 0));
            c = vc.getMaxCreationTime() < Long.MAX_VALUE;
            d = vc.getMaxCreationTime() < MetaDataUtil.getCreationTime(returnBuffer, 0);
            Log.warningf("doubleCheckVersion 1: %s %s", a && b, c && d);
            Log.warningf("doubleCheckVersion 2: %s %s %s %s", a, b, c, d);
            Log.warningf("MetaDataUtil.getCreationTime(returnBuffer, 0): %d",
                MetaDataUtil.getCreationTime(returnBuffer, 0));
            if (c && d) {
              Log.warningf("%d %d", vc.getMaxCreationTime(), MetaDataUtil.getCreationTime(returnBuffer, 0));
              Log.warningf("%s %s", new Date(vc.getMaxCreationTime()),
                  new Date(MetaDataUtil.getCreationTime(returnBuffer, 0)));
            }
          }

          // we include some extra checks below to avoid touching the returnBuffer unnecessarily
          if ((!vc.equals(VersionConstraint.greatest) && !vc.matches(MetaDataUtil.getVersion(returnBuffer,
              0))) || (vc.getMaxCreationTime() < Long.MAX_VALUE && vc.getMaxCreationTime() < MetaDataUtil.getCreationTime(
              returnBuffer, 0))) {
            returnBuffer = null;
          }
        }
        if (debugRetrieve) {
          if (returnBuffer != null) {
            System.out.println(
                "MetaDataUtil.getCompressedLength: " + MetaDataUtil.getCompressedLength(returnBuffer, 0));
          }
          Log.warning("returnBuffer: " + returnBuffer);
        }
        return returnBuffer;
      }
    }
  }

  int getResolvedOffset(DHTKey key, VersionConstraint vc) {
    int offset;

    offset = getRawOffset(key);
    if (debugRetrieve || Log.levelMet(Level.FINE)) {
      Log.warning("segment number: " + getSegmentNumber());
      Log.warning("key offset: ", key + " " + offset);
    }
    if (offset == noSuchKey) {
      if (debugRetrieve) {
        Log.warning("noSuchKey");
      }
      return noSuchKey;
    } else {
      if (offset < 0) {
        OffsetList offsetList;

        //System.out.printf("offset %d -offset %d\n", offset, -offset);
        if (debugRetrieve) {
          Log.warning("Looking in offset list: " + -offset);
        }
        offsetList = offsetListStore.getOffsetList(-offset);
        if (debugRetrieve) {
          Log.warning("offsetList: ", offsetList);
          offsetList.displayForDebug();
        }
        offset = offsetList.getOffset(vc, null);
        if (offset < 0) {
          offset = noSuchKey; // FUTURE - think about this
          if (debugRetrieve) {
            Log.warning("Couldn't find key in offset list. options: " + vc);
          }
        }
        if (debugRetrieve || Log.levelMet(Level.FINE)) {
          Log.warning("offset list offset: ", key + " " + offset);
        }
      }
      if (offset < 0) {
        Log.fine("getResolvedOffset < 0");
        return noSuchKey;
      } else {
        return offset;
      }
    }
  }

  void checksum_(DHTKey key, byte[] checksum) {
    int offset;

    offset = getRawOffset(key);
    if (offset >= 0) {

    } else {
      OffsetList offsetList;

      offsetList = offsetListStore.getOffsetList(-offset);
      for (int _offset : offsetList) {

      }
    }
  }
}
