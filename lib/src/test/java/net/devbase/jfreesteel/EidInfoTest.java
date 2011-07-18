/* Copyright (C) 2003, 2004 DECIS Lab, Delft, The Netherlands and
 * Copyright (C) 2003, 2004 Delft University of Technology
 *                          under the aegis of project COMBINED
 *
 * This file is released under the Combined License. Please see the
 * appropriate licensing document about the exact terms of the license.
 *
 * Created on Jul 17, 2011 by Filip Miletić <f.miletic@ewi.tudelft.nl>
 */
package net.devbase.jfreesteel;

import net.devbase.jfreesteel.EidInfo.Builder;
import net.devbase.jfreesteel.EidInfo.Tag;

/**
 * @author filmil@gmail.com (Filip Miletic)
 */
public class EidInfoTest extends EidTestCase {

    public void testKnownTag() {
        EidInfo info = new EidInfo.Builder()
            .addValue(EidInfo.Tag.DOC_REG_NO, "1000")
            .build();
        assertEquals("1000", info.get(Tag.DOC_REG_NO));
        assertContains("DOC_REG_NO=1000", info.toString());
    }

    public void testAddTwice() {
        try {
            new EidInfo.Builder()
                .addValue(EidInfo.Tag.DOC_REG_NO, "1000")
                .addValue(EidInfo.Tag.DOC_REG_NO, "2000")
                .build();
            fail("exception expectd");
        } catch (IllegalArgumentException expected) {}
    }

    public void testUnknownTag() {
        EidInfo info = new EidInfo.Builder()
            .addRawValue(-1, "foo".getBytes())
            .build();
        assertContains("-1 = foo (66:6F:6F)", info.toString());
    }

    public void testValidation() {
        EidInfo info = new EidInfo.Builder()
            .addValue(Tag.PERSONAL_NUMBER, "0000000000000")
            .build();
        assertEquals("0000000000000", info.get(Tag.PERSONAL_NUMBER));
    }

    public void testValidation_fails() {
        try {
            new EidInfo.Builder()
                .addValue(Tag.PERSONAL_NUMBER, "00")
                .build();
            fail("exception expected");
        } catch (IllegalArgumentException expected) {}
    }

    public void testPlaceOfBirth() {
        EidInfo info = new EidInfo.Builder()
            .addValue(Tag.PLACE_OF_BIRTH, "City")
            .addValue(Tag.COMMUNITY_OF_BIRTH, "Community")
            .addValue(Tag.STATE_OF_BIRTH, "State")
            .build();
        assertEquals("City, Community\nState", info.getPlaceOfBirthFull());
    }

    public void testGetPlaceFull() {
        Builder builder = new EidInfo.Builder()
            .addValue(Tag.STREET, "Street")
            .addValue(Tag.HOUSE_NUMBER, "55")
            .addValue(Tag.HOUSE_LETTER, "letter")
            .addValue(Tag.ENTRANCE, "entrance")
            .addValue(Tag.FLOOR, "floor")
            .addValue(Tag.PLACE, "Place")
            .addValue(Tag.COMMUNITY, "Community")
            .addValue(Tag.STATE, "State");
        assertEquals(
            "Street 55letter entrance, floor\n" +
            "Place, Community\n" +
            "State",
            builder.build().getPlaceFull(null, null, null));
        assertEquals(
            "Street 55letter entrance, floor\n" +
            "Place, Community\n" +
            "State",
            builder.build().getPlaceFull("", "", ""));

        builder.addValue(Tag.APPARTMENT_NUMBER, "1212");
        assertEquals(
            "Street 55letter entrance, floor, 1212\n" +
            "Place, Community\n" +
            "State",
            builder.build().getPlaceFull(null, null, null));
    }

    public void testGetPlaceFull_noEntranceAndNoFloor() {
        Builder builder = new EidInfo.Builder()
            .addValue(Tag.STREET, "Street")
            .addValue(Tag.HOUSE_NUMBER, "55")
            .addValue(Tag.HOUSE_LETTER, "L")
            .addValue(Tag.APPARTMENT_NUMBER, "1212")
            .addValue(Tag.PLACE, "Place")
            .addValue(Tag.COMMUNITY, "Community")
            .addValue(Tag.STATE, "State");
        assertEquals(
            "Street 55L/1212\n" +
            "Place, Community\n" +
            "State",
            builder.build().getPlaceFull(null, null, null));
    }

    public void testGetPlaceFull_srb() {
        Builder builder = new EidInfo.Builder()
            .addValue(Tag.STREET, "Street")
            .addValue(Tag.HOUSE_NUMBER, "55")
            .addValue(Tag.HOUSE_LETTER, "L")
            .addValue(Tag.APPARTMENT_NUMBER, "1212")
            .addValue(Tag.PLACE, "Place")
            .addValue(Tag.COMMUNITY, "Community")
            .addValue(Tag.STATE, "SRB");
        assertEquals("Street 55L/1212\n" +
                "Place, Community\n" +
                "REPUBLIKA SR BIJA", builder.build().getPlaceFull(null, null, null));
    }

    public void testGetPlaceFull_formatted() {
        Builder builder = new EidInfo.Builder()
            .addValue(Tag.STREET, "Street")
            .addValue(Tag.HOUSE_NUMBER, "55")
            .addValue(Tag.HOUSE_LETTER, "L")
            .addValue(Tag.ENTRANCE, "E")
            .addValue(Tag.FLOOR, "666")
            .addValue(Tag.PLACE, "Place")
            .addValue(Tag.COMMUNITY, "Community")
            .addValue(Tag.STATE, "State");
        assertEquals(
            "Street 55L AA E, BB 666\n" +
            "Place, Community\n" +
            "State",
            builder.build().getPlaceFull("AA %s", "BB %s", "CC %s"));
    }
}
