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
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

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

    /** The list of known EID attributes, used to identify smartcards. */
    private static final Iterable<byte[]> KNOWN_EID_ATTRIBUTES = ImmutableList.of(
        // Add more as more become available.
        Utils.asByteArray(
            0x3B, 0xB9, 0x18, 0x00, 0x81, 0x31, 0xFE, 0x9E, 
            0x80, 0x73, 0xFF, 0x61, 0x40, 0x83, 0x00, 0x00,
            0x00, 0xDF));
    /** Document data */
    private static final byte[] DOCUMENT_FILE  = {0x0F, 0x02};
    /** Personal data */
    private static final byte[] PERSONAL_FILE = {0x0F, 0x03};
    /** 
     * Place of residence.
     * <p>
     * Variable length.
     */
    private static final byte[] RESIDENCE_FILE = {0x0F, 0x04};
    /**
     * Personal photo.
     * <p>
     * JPEG format.
     */
    private static final byte[] PHOTO_FILE = {0x0F, 0x06};

    private static final int BLOCK_SIZE = 0xFF;
    
    private Card card;
    private CardChannel channel;
    
    public EidCard(Card card) 
        throws IllegalArgumentException, SecurityException, IllegalStateException {
        final byte[] atrBytes = card.getATR().getBytes();
        Preconditions.checkArgument(
            isKnownAtr(atrBytes), 
            String.format("EidCard: Card is not recognized as Serbian eID. Card ATR: %s", 
                Utils.bytes2HexString(atrBytes)));
        this.card = card;
        channel = card.getBasicChannel();
    }
    
    private boolean isKnownAtr(byte[] cardAtr) {
        for (byte[] eid_atr : KNOWN_EID_ATTRIBUTES) {
            if (Arrays.equals(cardAtr, eid_atr)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Subdivides the byte array into byte sub-arrays, keyed by their tags
     * <p>
     * Encoding sequence is a repeated sequence of the following.
     * <ol>
     *   <li>The tag, encoded as little-endian 16-bit number
     *   <li>The length of data, in bytes, as little-endian 16-bit number
     *   <li>The data bytes, as many as determined by length.
     * </ol> 
     * [tag 16bit LE] [len 16bit LE] [len bytes of data] | [fld] [06] ...
     * 
     * @return a map of integer tags to corresponding byte chunks.
     */
    @VisibleForTesting 
    static Map<Integer, byte[]> parseTlv(byte[] bufferArray) {
        ImmutableMap.Builder<Integer, byte[]> builder = ImmutableMap.builder();
        
        int i = 0;
        while (i + 3 < bufferArray.length) {
            int length = ((0xFF & bufferArray[i + 3]) << 8) + (0xFF & bufferArray[i + 2]);
            int tag = ((0xFF & bufferArray[i + 1]) << 8) + (0xFF & bufferArray[i + 0]);
            
            // is there a new tag?
            if (length >= bufferArray.length) {
                break;
            }
            builder.put(tag, Arrays.copyOfRange(bufferArray, i + 4, i + 4 + length));

            i += 4 + length;
        }
        
        return builder.build();
    }

    /**
     * Read elementary file (EF) contents, selecting by file path.
     * <p>
     * The file length is read at 4B offset from the file. The method is not thread safe. Exclusive
     * card access should be enabled before calling the method.
     * <p>
     * TODO: Refactor to be in line with ISO7816-4 and BER-TLV, removing "magic" headers
     */
    private byte[] readElementaryFile(byte[] name, boolean stripHeader) throws CardException {
        selectFile(name);
    
        byte[] header = readFromFile(0, 6);
        // Missing files have header filled with 0xFF
        int i = 0;
        while (i < header.length && header[i] == 0xFF) {
            i++;
        }
        if (i == header.length) {
            throw new CardException("Read EF file failed: File header is missing");
        }
        
        // Total EF length: data as 16bit LE at 4B offset
        int length = ((0xFF&header[5])<<8) + (0xFF&header[4]);   
        int offset = stripHeader ? 10 : 6;

        return readFromFile(offset, length);
    }
    
    private byte[] readFromFile(int offset, int length) throws CardException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (length > 0) {
            int readSize = Math.min(length, BLOCK_SIZE);
            ResponseAPDU response = channel.transmit(
                new CommandAPDU(0x00, 0xB0, offset >> 8, offset & 0xFF, readSize));
            if (response.getSW() != 0x9000) {
                throw new CardException(
                    String.format("Read binary failed: offset=%d, length=%d, status=%s", 
                        offset, length, Utils.int2HexString(response.getSW())));
            }
            try {
                byte[] data = response.getData();
                out.write(data);
                offset += data.length;
                length -= data.length;
            } catch (IOException e) {
                throw new CardException("Read binary failed: Could not write byte stream");
            }
        }
        return out.toByteArray();
    }
    
    /** Selects the elementary file to read, based on the name passed in. */
    private void selectFile(byte[] name) throws CardException {
        ResponseAPDU r = channel.transmit(new CommandAPDU(0x00, 0xA4, 0x08, 0x00, name));
        if (r.getSW() != 0x9000) {
            throw new CardException(
                String.format("Select failed: name=%s, status=%s", 
                    Utils.bytes2HexString(name), Utils.int2HexString(r.getSW())));
        }
    }
    
    /** 
     * Reads the photo data from the card.
     */
    public Image readEidPhoto() throws CardException {
        try {
            logger.info("photo exclusive");
            card.beginExclusive();

            // Read binary into buffer
            byte[] bytes = readElementaryFile(PHOTO_FILE, true);

            try {
                return ImageIO.read(new ByteArrayInputStream(bytes));
            } catch (IOException e) {
                throw new CardException("Photo reading error", e);
            }
        } finally {
            card.endExclusive();
            logger.info("photo exclusive free");            
        }
    }
    
    public EidInfo readEidInfo() throws CardException, Exception {
        try {
            logger.info("exclusive");
            card.beginExclusive();
            channel = card.getBasicChannel();
            
            Map<Integer, byte[]> document  = parseTlv(readElementaryFile(DOCUMENT_FILE, false));
            Map<Integer, byte[]> personal  = parseTlv(readElementaryFile(PERSONAL_FILE, false));
            Map<Integer, byte[]> residence = parseTlv(readElementaryFile(RESIDENCE_FILE, false));

            EidInfo info = new EidInfo();

            // tags: 1545 - 1553
            // 1545 = SRB
            info.setDocRegNo(Utils.bytes2UTF8String((byte[]) document.get(1546)));
            // 1547 = ID
            // 1548 = ID<docRegNo>
            info.setIssuingDate(Utils.bytes2UTF8String((byte[]) document.get(1549)));
            info.setExpiryDate(Utils.bytes2UTF8String((byte[]) document.get(1550)));
            info.setIssuingAuthority(Utils.bytes2UTF8String((byte[]) document.get(1551)));
            // 1552 = SC
            // 1553 = SC

            // tags: 1558 - 1567
            info.setPersonalNumber(Utils.bytes2UTF8String((byte[]) personal.get(1558)));
            info.setSurname(Utils.bytes2UTF8String((byte[]) personal.get(1559)));
            info.setGivenName(Utils.bytes2UTF8String((byte[]) personal.get(1560)));
            info.setParentGivenName(Utils.bytes2UTF8String((byte[]) personal.get(1561)));
            info.setSex(Utils.bytes2UTF8String((byte[]) personal.get(1562)));
            info.setPlaceOfBirth(Utils.bytes2UTF8String((byte[]) personal.get(1563)));
            info.setCommunityOfBirth(Utils.bytes2UTF8String((byte[]) personal.get(1564)));
            info.setStateOfBirth(Utils.bytes2UTF8String((byte[]) personal.get(1565)));        
            info.setDateOfBirth(Utils.bytes2UTF8String((byte[]) personal.get(1566)));
            // 1567 = SRB (stateOfBirth code?)
        
            // tags: 1568 .. 1578
            info.setState(Utils.bytes2UTF8String((byte[]) residence.get(1568)));
            info.setCommunity(Utils.bytes2UTF8String((byte[]) residence.get(1569)));
            info.setPlace(Utils.bytes2UTF8String((byte[]) residence.get(1570)));
            info.setStreet(Utils.bytes2UTF8String((byte[]) residence.get(1571)));
            info.setHouseNumber(Utils.bytes2UTF8String((byte[]) residence.get(1572)));        
            // TODO: What about tags 1573 .. 1577_
            // info.setHouseLetter(Utils.bytes2UTF8String((byte[]) residence.get(1573))); // ??
            // info.setEntrance(Utils.bytes2UTF8String((byte[]) residence.get(1576)));    // ??
            // info.setFloor(Utils.bytes2UTF8String((byte[]) residence.get(1577)));       // ??
            info.setAppartmentNumber(Utils.bytes2UTF8String((byte[]) residence.get(1578)));

            // FIXME: log residence info so all users can check and copy-paste missing tags
            logger.error(Utils.map2UTF8String(residence));

            return info;

        } finally {
            card.endExclusive();
            logger.info("exclusive free");            
        }
    }

    /** Returns a debug string consisting of per-file debug info.*/
    public String debugEidInfo() throws CardException {
        try {
            logger.debug("debug exclusive ask");
            card.beginExclusive();
            logger.debug("debug exclusive granted");

            channel = card.getBasicChannel();
            
            Map<Integer, byte[]> document  = parseTlv(readElementaryFile(DOCUMENT_FILE, false));
            Map<Integer, byte[]> personal  = parseTlv(readElementaryFile(PERSONAL_FILE, false));
            Map<Integer, byte[]> residence = parseTlv(readElementaryFile(RESIDENCE_FILE, false));

            String out = "";
            out += "Document:\n"  + Utils.map2UTF8String(document);
            out += "Personal:\n"  + Utils.map2UTF8String(personal);
            out += "Residence:\n" + Utils.map2UTF8String(residence);

            return out;

        } finally {
            card.endExclusive();
            logger.debug("debug exclusive free");
        }        
    }
    
    /** Disconnects, but doesn't reset the card. */
    public void disconnect() throws CardException {
        disconnect(false);
    }

    private void disconnect(boolean reset) throws CardException {
        card.disconnect(reset);
        card = null;
    }
    
    @Override
    protected void finalize() {
        try {
            if (card != null) {
                disconnect(false);
            }
        } catch (CardException error) {
            // Can't throw an exception from within finalize, else object finalization
            // will be halted by JVM, which is bad.
            // can't log to instance logger because logger may already have been destroyed.  So just
            // write log output and hope for the best.
            LoggerFactory.getLogger(EidCard.class).warn(error.getMessage());
        }  
    }
}
