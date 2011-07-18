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

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

/**
 * Simple class to hold and reformat data read from eID
 *
 * @author nikolic.alek@gmail.com (Aleksandar Nikolic)
 */
public class EidInfo {

    private static final Map<Integer, Tag> enumsByTag = Maps.newHashMap();

    /** EID information codes. */
    public enum Tag {
        /** Country code. */
        SRB(1545),
        /** Registered document number. */
        DOC_REG_NO(1546),
        ID(1547),  // ?
        /** The unique document ID of this document. */
        ID_DOC_REG_NO(1548),
        /** The issuing date, e.g. 01.01.2011. */
        ISSUING_DATE(1549),
        /** The date that the ID expires */
        EXPIRY_DATE(1550),
        /** The authoritu, e.g. "Ministry of the Interior". */
        ISSUING_AUTHORITY(1551),
        /** The person's unique identifier number.
         *
         * While mostly unique, due to the non-bulletproof number allocation scheme, has actually
         * been known to repeat for some rare individuals.
         */
        PERSONAL_NUMBER(1558, new ValidatePersonalNumber()),
        /** Person's last name, e.g. "Smith" for some John Smith */
        SURNAME(1559),
        /** The given name, e.g. "John" for some John Smith. */
        GIVEN_NAME(1560),
        /**
         * The parent's given name, the usual 'parenthood' middle name used to disambiguate
         * similarly named persons.
         * <p>
         * E.g. "Wiley" for some John Wiley Smith.
         */
        PARENT_GIVEN_NAME(1561),
        /** The gender of the person. */
        SEX(1562),
        /** The plae the person was born in, e.g. "Belgrade" */
        PLACE_OF_BIRTH(1563),
        /** ? */
        COMMUNITY_OF_BIRTH(1564),
        STATE_OF_BIRTH(1565),
        DATE_OF_BIRTH(1566),
        STATE_OF_BIRTH_CODE(1567),  // ?
        STATE(1568),
        COMMUNITY(1569),
        PLACE(1570),
        STREET(1571),
        HOUSE_NUMBER(1572),
        // TODO(nikolic.alek): What about tags 1573 .. 1577_
        HOUSE_LETTER(1573),  // ?
        ENTRANCE(1576),  // ?
        FLOOR(1577),  // ?
        APPARTMENT_NUMBER(1578);

        private final int value;
        private final Predicate<String> validator;
        /** Initializes a tag with the corresponding raw encoding value */
        Tag(int value) {
            this(value, Predicates.<String>alwaysTrue());
        }
        /**
         * Initializes a tag with the corresponding raw encoding value, and a
         * validator.
         * <p>
         * The validator is invoked to check whether the given raw value parsees into
         * a sensible value for the field.
         */
        Tag(int value, Predicate<String> validator) {
            this.value = value;
            this.validator = validator;
            enumsByTag.put(value, this);
        }
        /** Gets the numeric tag corresponding to this enum. */
        public int get() {
            return value;
        }
        /** Runs a validator for this tag on the supplied value.
         *
         * @return true if the value is valid; false otherwise.
         */
        public boolean validate(String value) {
            return validator.apply(value);
        }
    }

    private Map<Tag, String> knownValues;
    private Map<Integer, byte[]> unknownValues;

    /** Builds an instance of EID info. */
    public static class Builder {
        ImmutableMap.Builder<Tag, String> builder;
        ImmutableMap.Builder<Integer, byte[]> unknownBuilder;

        public Builder() {
            builder = ImmutableMap.builder();
            unknownBuilder = ImmutableMap.builder();
        }

        /**
         * Adds the raw byte value to the information builder.
         *
         *  @throws IllegalArgumentException if the raw value supplied fails validation, or if
         *      the same tag is added twice.
         */
        public Builder addRawValue(int rawTag, byte[] rawValue) {
            Tag tag = enumsByTag.get(rawTag);
            if (tag != null) {
                String value = Utils.bytes2UTF8String(rawValue);
                Preconditions.checkArgument(
                    tag.validate(value),
                    String.format("Value '%s' not valid for tag '%s'", value, tag));
                builder.put(tag, value);
            } else {
                unknownBuilder.put(rawTag, rawValue);
            }
            return this;
        }

        /**
         * Adds the value to the information builder.
         *
         *  @throws IllegalArgumentException if the raw value supplied fails validation, or if
         *      the same tag is added twice.
         */
        public Builder addValue(Tag tag, String value) {
            return addRawValue(tag.get(), value.getBytes(Charsets.UTF_8));
        }

        public EidInfo build() {
            return new EidInfo(builder.build(), unknownBuilder.build());
        }
    }

    private EidInfo(Map<Tag, String> knownFields, Map<Integer, byte[]> unknownFields) {
        this.knownValues = knownFields;
        this.unknownValues = unknownFields;
    }

    /** Returns the value associated with the supplied tag. */
    public String get(Tag tag) {
        return knownValues.get(tag);
    }

    private boolean has(Tag tag) {
        return !Strings.isNullOrEmpty(get(tag));
    }

    /**
     * Get given name, parent given name and a surname as a single string.
     *
     * @return Nicely formatted full name
     */
    public String getNameFull() {
        return String.format(
            "%s %s %s", get(Tag.GIVEN_NAME), get(Tag.PARENT_GIVEN_NAME), get(Tag.SURNAME));
    }

    private void appendTo(StringBuilder builder, Tag tag, String separator) {
        if (has(tag)) {
            builder.append(String.format("%s%s", separator, get(tag)));
        }
    }

    private void appendTo(StringBuilder builder, String format, Tag tag, String separator) {
        if (has(tag)) {
            builder.append(separator);
            builder.append(String.format(format, get(tag)));
        }
    }

    private void appendTo(StringBuilder builder, Tag tag) {
        appendTo(builder, tag, "");
    }

    private String sanitizeFormat(String format) {
        return (format != null && format.contains("%s"))
            ? format
            : "%s";
    }

    /**
     * Get place of residence as multiline string. Format paramters can be used to provide better
     * output or null/empty strings can be passed for no special formating.
     *
     * For example if floorLabelFormat is "%s. sprat" returned string will contain "5. sprat" for
     * floor number 5.
     *
     * Recommended values for Serbian are "ulaz %s", "%s. sprat" and "br. %s"
     *
     * @param entranceLabelFormat String to format entrance label or null
     * @param floorLabelFormat String to format floor label or null
     * @param appartmentLabelFormat String to format appartment label or null
     * @return Nicely formatted place of residence as multiline string
     */
    public String getPlaceFull(
            String entranceLabelFormat, String floorLabelFormat, String appartmentLabelFormat) {
        StringBuilder out = new StringBuilder();

        entranceLabelFormat = sanitizeFormat(entranceLabelFormat);
        floorLabelFormat = sanitizeFormat(floorLabelFormat);
        appartmentLabelFormat = sanitizeFormat(appartmentLabelFormat);

        appendTo(out, Tag.STREET);
        appendTo(out, Tag.HOUSE_NUMBER, " ");
        appendTo(out, Tag.HOUSE_LETTER);
        // For entranceLabel = "ulaz %s" gives "Neka ulica 11A ulaz 2"
        appendTo(out, entranceLabelFormat, Tag.ENTRANCE, " ");

        // For floorLabel = "%s. sprat" gives "Neka ulica 11 ulaz 2, 5. sprat"
        appendTo(out, floorLabelFormat, Tag.FLOOR, ", ");

        if (has(Tag.APPARTMENT_NUMBER)) {
            // For appartmentLabel "br. %s" gives "Neka ulica 11 ulaz 2, 5. sprat, br. 4"
            if (has(Tag.ENTRANCE) || has(Tag.FLOOR)) {
                out.append(", " + String.format(appartmentLabelFormat, get(Tag.APPARTMENT_NUMBER)));
            } else {
                // short form: Neka ulica 11A/4
                out.append("/" + get(Tag.APPARTMENT_NUMBER));
            }
        }

        appendTo(out, Tag.PLACE, "\n");
        appendTo(out, Tag.COMMUNITY, ", ");

        out.append("\n");
        String rawState = get(Tag.STATE);
        out.append("SRB".equals(rawState)
            ? "REPUBLIKA SRBIJA"
            : rawState);
        return out.toString();
    }

    /**
     * Get full place of birth as a multiline string, including community and state if present.
     *
     * @return Nicely formatted place of birth as a multiline string.
     */
    public String getPlaceOfBirthFull()
    {
        StringBuilder out = new StringBuilder();

        appendTo(out, Tag.PLACE_OF_BIRTH);
        appendTo(out, Tag.COMMUNITY_OF_BIRTH, ", ");
        appendTo(out, Tag.STATE_OF_BIRTH, "\n");
        return out.toString();
    }

    @Override
    public String toString() {
        return String.format("%s\n%s\n",
            knownValues,
            Utils.map2UTF8String(unknownValues));
    }

    /**
     * Checks whether the personal ID number is a valid number.
     * <p>
     * Currently only the format is checked, but not the checksum.
     */
    private static class ValidatePersonalNumber implements Predicate<String> {
        public boolean apply(String personalNumber) {
            // there are valid personal numbers with invalid checksum, check for format only
            Pattern pattern = Pattern.compile("^[0-9]{13}$", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(personalNumber);
            return matcher.matches();
        }
    }
}
