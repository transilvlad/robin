package com.mimecast.robin.mtasts.assets;

import org.xbill.DNS.ARecord;
import org.xbill.DNS.MXRecord;
import org.xbill.DNS.Record;

/**
 * DNS Record.
 * <p>Wrapper for DNS Java MXRecord.
 *
 * @see MXRecord
 */
public final class XBillDnsRecord implements DnsRecord {

    /**
     * MXRecord instance.
     */
    private final Record record;

    /**
     * Constructs a new DnsRecord instance.
     *
     * @param record Record instance.
     */
    public XBillDnsRecord(Record record) {
        this.record = record;
    }

    /**
     * Gets value.
     *
     * @return Value string.
     */
    public String getValue() {
        return record instanceof MXRecord ?
                record.getAdditionalName().toString(true) :
                (
                        record instanceof ARecord ?
                                ((ARecord) record).getAddress().getHostAddress() :
                                null
                );
    }

    /**
     * Gets priority.
     *
     * @return Priority integer.
     */
    public int getPriority() {
        return record instanceof MXRecord ? ((MXRecord) record).getPriority() : -1;
    }
}
