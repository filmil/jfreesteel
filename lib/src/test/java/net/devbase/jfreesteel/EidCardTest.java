package net.devbase.jfreesteel;

import java.util.Arrays;
import java.util.Map;

import junit.framework.TestCase;

public class EidCardTest extends TestCase {
    
    public void testParseTlv_basic() {
        Map<Integer, byte[]> result = EidCard.parseTlv(
            Utils.asByteArray(0xfe, 0xca, 0x01, 0x00, 0xfe));
        
        assertTrue(
            Arrays.equals(Utils.asByteArray(0xfe), result.get(0xcafe)));
    }
    
    public void testParseTlv_complex() {
        Map<Integer, byte[]> result = EidCard.parseTlv(
            Utils.asByteArray(
                0xfe, 0xca,  // 0xcafe 
                0x01, 0x00,  // 0x1
                0xfe, // 1-byte data
                0xbe, 0xba,  // 0xaabb
                0x05, 0x00,  // 0x5
                0x01, 0x02, 0x03, 0x04, 0x05, // 5-byte data
                0xff, 0xff   // Some extra crud, ignored
                ));  
        
        assertTrue(Arrays.equals(
            Utils.asByteArray(0xfe), 
            result.get(0xcafe)));
        assertTrue(Arrays.equals(
            Utils.asByteArray(0x01, 0x02, 0x03, 0x04, 0x05), 
            result.get(0xbabe)));
    }
}
