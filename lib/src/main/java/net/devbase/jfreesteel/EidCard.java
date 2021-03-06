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

            out.put(new Integer(tag), Arrays.copyOfRange(bytes, i+4, i+4+len));

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
    
    public EidInfo readEidInfo() throws CardException, Exception {
        try {
            logger.info("exclusive");
            card.beginExclusive();
            channel = card.getBasicChannel();
            
            final Map<Integer, byte[]> document  = parseTLV(readElementaryFile(DOCUMENT_FILE, false));
            final Map<Integer, byte[]> personal  = parseTLV(readElementaryFile(PERSONAL_FILE, false));
            final Map<Integer, byte[]> residence = parseTLV(readElementaryFile(RESIDENCE_FILE, false));

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

    public String debugEidInfo() throws CardException {
        try {
            logger.debug("debug exclusive ask");
            card.beginExclusive();
            logger.debug("debug exclusive granted");

            channel = card.getBasicChannel();
            
            final Map<Integer, byte[]> document  = parseTLV(readElementaryFile(DOCUMENT_FILE, false));
            final Map<Integer, byte[]> personal  = parseTLV(readElementaryFile(PERSONAL_FILE, false));
            final Map<Integer, byte[]> residence = parseTLV(readElementaryFile(RESIDENCE_FILE, false));

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
    
/*
    private X509Certificate readCertificate(byte[] name, CertificateFactory factory)
        throws CardException, CertificateException {
        byte[] bytes = readElementaryFile(AUTH_CERT_FILE, true);
        
        return factory.generateCertificate(new ByteArrayInputStream(bytes));
    }
    
    public List<X509Certificate> readAuthCertChain() throws CertificateException {
        List<X509Certificate> chain = new LinkedList<X509Certificate>();
        CertificateFactory factory = CertificateFactory.getInstance("X.509");

        chain.add(readCertificate(AUTH_CERT_FILE, factory));
        chain.add(readCertificate(INTERM_CERT_FILE, factory));

        return chain;
    }
    
    public List<X509Certificate> readSigningCertChain()
    {
        List<X509Certificate> chain = new LinkedList<X509Certificate>();
        CertificateFactory factory = CertificateFactory.getInstance("X.509");

        chain.add(readCertificate(SIGNING_CERT_FILE, factory));
        chain.add(readCertificate(INTERM_CERT_FILE, factory));

        return chain;
    }
*/
    
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

/*    
    public interface EidPhotoAsyncCallback {
        public void ready(Image image);
        public void error(CardException e);
    }

    public void readEidPhotoAsync(final EidPhotoAsyncCallback callback) {
        Thread reader = new Thread(new Runnable() {
            public void run() {
                try {
                    Image photo = readEidPhoto();
                    callback.ready(photo);
                } catch (CardException e) {
                    callback.error(e);
                }
            }
        });
        reader.start();
    }
*/    
}
