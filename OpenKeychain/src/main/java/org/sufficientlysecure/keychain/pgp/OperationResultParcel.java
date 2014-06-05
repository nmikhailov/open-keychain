package org.sufficientlysecure.keychain.pgp;

import android.R;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;

/** Represent the result of an operation.
 *
 * This class holds a result and the log of an operation. It can be subclassed
 * to include typed additional information specific to the operation. To keep
 * the class structure (somewhat) simple, this class contains an exhaustive
 * list (ie, enum) of all possible log types, which should in all cases be tied
 * to string resource ids.
 *
 */
public class OperationResultParcel implements Parcelable {
    /** Holds the overall result. A value of 0 is considered a success, all
     * other values may represent failure or varying degrees of success. */
    final int mResult;

    /// A list of log entries tied to the operation result.
    final ArrayList<LogEntryParcel> mLog;

    public OperationResultParcel(int result, ArrayList<LogEntryParcel> log) {
        mResult = result;
        mLog = log;
    }

    public OperationResultParcel(Parcel source) {
        mResult = source.readInt();
        mLog = source.createTypedArrayList(LogEntryParcel.CREATOR);
    }

    public boolean isSuccessful() {
        return mResult == 0;
    }

    /** One entry in the log. */
    public static class LogEntryParcel implements Parcelable {
        final LogType mType;
        final LogLevel mLevel;
        final String[] mParameters;

        public LogEntryParcel(LogType type, LogLevel level, String[] parameters) {
            mType = type;
            mLevel = level;
            mParameters = parameters;
        }

        public LogEntryParcel(Parcel source) {
            mType = LogType.values()[source.readInt()];
            mLevel = LogLevel.values()[source.readInt()];
            mParameters = source.createStringArray();
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mType.ordinal());
            dest.writeInt(mLevel.ordinal());
            dest.writeStringArray(mParameters);
        }

        public static final Creator<LogEntryParcel> CREATOR = new Creator<LogEntryParcel>() {
            public LogEntryParcel createFromParcel(final Parcel source) {
                return new LogEntryParcel(source);
            }

            public LogEntryParcel[] newArray(final int size) {
                return new LogEntryParcel[size];
            }
        };

    }

    public static enum LogType {
        // TODO add actual log entry types here
        MSG_IMPORT_OK (R.string.copy),
        MSG_IMPORT_FAILED (R.string.cancel);

        private int mMsgId;
        LogType(int msgId) {
            mMsgId = msgId;
        }
    }

    /** Enumeration of possible log levels. */
    public static enum LogLevel {
        DEBUG,
        INFO,
        WARN,
        /** If any ERROR log entry is included in the result, the overall operation should have failed. */
        ERROR,
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mResult);
        dest.writeTypedList(mLog);
    }

    public static final Creator<OperationResultParcel> CREATOR = new Creator<OperationResultParcel>() {
        public OperationResultParcel createFromParcel(final Parcel source) {
            return new OperationResultParcel(source);
        }

        public OperationResultParcel[] newArray(final int size) {
            return new OperationResultParcel[size];
        }
    };

}
