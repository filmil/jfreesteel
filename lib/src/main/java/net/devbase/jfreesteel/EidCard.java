/*
 * jfreesteel: Serbian eID Viewer Library (GNU LGPLv3)
 * Copyright (C) 2011 Goran Rakic
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version
 * 3.0 as published by the Free Software Foundation.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, see
 * http://www.gnu.org/licenses/.
 */

package net.devbase.jfreesteel;

import java.awt.Image;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EidCard is a wrapper providing an interface for reading data
 * from the Serbian eID card. Public read*() methods allow you to
 * get specific data about card holder and certificates stored on
 * the card.
 *
 * It is not advised to initialize this class directly. Instead you
 * should initialize Reader class and assign the listener for the
 * card insertion/removal events. The listener will receive EidCard
 * object when the card is inserted into the terminal.
 *
 * @author Goran Rakic (grakic@devbase.net)
 */
@SuppressWarnings("restriction") // Various javax.smartcardio.*
public class EidCard {

    private final static Logger logger = LoggerFactory.getLogger(EidCard.class);

    private Card card = null;
    private CardChannel channel;

    public EidCard(final Card card)
        throws IllegalArgumentException, SecurityException, IllegalStateException {
        // Check if the card ATR is recognized
        if(!knownATR(card.getATR().getBytes())) {
            throw new IllegalArgumentException(
                "EidCard: Card is not recognized as Serbian eID. Card ATR: " +
                Utils.bytes2HexString(card.getATR().getBytes()));
        }

        this.card = card;
        channel = card.getBasicChannel();
    }

    private boolean knownATR(byte[] card_atr) {
        for(byte[] eid_atr:known_eid_atrs) {
            if(Arrays.equals(card_atr, eid_atr)) {
                return true;
            }
        }
        return false;
    }

    private static final byte[][] known_eid_atrs = {
        {(byte) 0x3B, (byte) 0xB9, (byte) 0x18, (byte) 0x00, (byte) 0x81, (byte) 0x31, (byte) 0xFE,
         (byte) 0x9E, (byte) 0x80, (byte) 0x73, (byte) 0xFF, (byte) 0x61, (byte) 0x40, (byte) 0x83,
         (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0xDF},
    };

    private static final byte[] DOCUMENT_FILE  = {0x0F, 0x02}; // Document data
    private static final byte[] PERSONAL_FILE  = {0x0F, 0x03}; // Personal data
    private static final byte[] RESIDENCE_FILE = {0x0F, 0x04}; // Place of residence, var length
    private static final byte[] PHOTO_FILE     = {0x0F, 0x06}; // Personal photo in JPEG format

    // Intermediate CA gradjani public X.509 certificate
    @SuppressWarnings("unused")
    private static final byte[] INTERM_CERT_FILE  = {0x0F, 0x11};

    // Public X.509 certificate for qualified (Non Repudiation) signing
    @SuppressWarnings("unused")
    private static final byte[] SIGNING_CERT_FILE = {0x0F, 0x10};

    // Public X.509 certificate for authentication
    @SuppressWarnings("unused")
    private static final byte[] AUTH_CERT_FILE    = {0x0F, 0x08};

    private static final int BLOCK_SIZE = 0xFF;

    private Map<Integer, byte[]> parseTLV(byte[] bytes) {
        HashMap<Integer, byte[]> out = new HashMap<Integer, byte[]>();

        // [fld 16bit LE] [len 16bit LE] [len bytes of data] | [fld] [06] ...

        int i = 0;
        while(i+3 < bytes.length) {
            int len = ((0xFF&bytes[i+3])<<8) + (0xFF&bytes[i+2]);
            int tag = ((0xFF&bytes[i+1])<<8) + (0xFF&bytes[i+0]);

            // is there a new tag?
            if(len >= bytes.length) break;

            out.put(tag, Arrays.copyOfRange(bytes, i+4, i+4+len));

            i += 4+len;
        }

        return out;
    }

    /**
     * Read EF content, selecting by file path.
     *
     * The file length is read at 4B offset from the file. The method is not thread safe. Exclusive
     * card access should be enabled before calling the method.
     *
     * TODO: Refactor to be in line with ISO7816-4 and BER-TLV, removing "magic" headers
     */
    private byte[] readElementaryFile(byte[] name, boolean strip_heading_tlv) throws CardException {

        selectFile(name);

        // Read first 6 bytes from the EF
        byte[] header = readBinary(0, 6);

        // Missing files have header filled with 0xFF
        int i = 0;
        while (i < header.length && header[i] == 0xFF) {
            i++;
        }
        if (i == header.length) {
            throw new CardException("Read EF file failed: File header is missing");
        }

        // Total EF length: data as 16bit LE at 4B offset
        final int length = ((0xFF&header[5])<<8) + (0xFF&header[4]);
        final int offset = strip_heading_tlv ? 10 : 6;

        // Read binary into buffer
        return readBinary(offset, length);
    }

    private byte[] readBinary(int offset, int length) throws CardException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (length > 0) {
            int block = Math.min(length, BLOCK_SIZE);
            ResponseAPDU r = channel.transmit(new CommandAPDU(0x00, 0xB0, offset >> 8, offset & 0xFF, block));
            if (r.getSW() != 0x9000) {
                throw new CardException("Read binary failed: " + Utils.int2HexString(r.getSW()));
            }

            try {
                byte[] data = r.getData();
                int data_len = data.length;

                out.write(data);
                offset += data_len;
                length -= data_len;
            } catch (IOException e) {
                throw new CardException("Read binary failed: Could not write byte stream");
            }
        }

        return out.toByteArray();
    }

    private void selectFile(byte[] name) throws CardException {
        ResponseAPDU r = channel.transmit(new CommandAPDU(0x00, 0xA4, 0x08, 0x00, name));
        if(r.getSW() != 0x9000) {
            throw new CardException("Select failed: " + Utils.int2HexString(r.getSW()));
        }
    }

    public Image readEidPhoto() throws CardException {
        try {
            logger.info("photo exclusive");
            card.beginExclusive();

            // Read binary into buffer
            byte[] bytes = readElementaryFile(PHOTO_FILE, true);

            try {
                return ImageIO.read(new ByteArrayInputStream(bytes));
            } catch (IOException e) {
                throw new CardException("Photo reading error: " + e.getMessage(), e);
            }
        } finally {
            card.endExclusive();
            logger.info("photo exclusive free");
        }
    }

    private void addAllToBuilder(EidInfo.Builder builder, Map<Integer, byte[]> rawTagMap) {
        for (Map.Entry<Integer, byte[]> entry : rawTagMap.entrySet()) {
            builder.addRawValue(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Reads the EID information from this card.
     * @throws CardException in case of any problems with the card itself.
     */
    public EidInfo readEidInfo() throws CardException {
        try {
            logger.info("exclusive");
            card.beginExclusive();
            channel = card.getBasicChannel();

            EidInfo.Builder builder = new EidInfo.Builder();
            addAllToBuilder(builder, parseTLV(readElementaryFile(DOCUMENT_FILE, false)));
            addAllToBuilder(builder, parseTLV(readElementaryFile(PERSONAL_FILE, false)));
            addAllToBuilder(builder, parseTLV(readElementaryFile(RESIDENCE_FILE, false)));
            return builder.build();

        } finally {
            card.endExclusive();
            logger.info("exclusive free");
        }
    }

    public String debugEidInfo() throws CardException {
        EidInfo info = readEidInfo();
        return info.toString();
    }

    public void disconnect(boolean reset) throws CardException {
        card.disconnect(reset);
        card = null;
    }

    @Override
    protected void finalize() throws Throwable {
        if (card != null) {
            disconnect(false);
        }
    }
}
