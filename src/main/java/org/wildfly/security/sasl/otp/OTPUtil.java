/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2015 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wildfly.security.sasl.otp;

import static org.wildfly.security._private.ElytronMessages.log;
import static org.wildfly.security.password.interfaces.OneTimePassword.*;
import static org.wildfly.security.sasl.otp.OTP.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

import javax.security.sasl.SaslException;

import org.wildfly.security.sasl.util.SaslMechanismInformation;
import org.wildfly.security.util.ByteIterator;
import org.wildfly.security.util.CodePointIterator;

/**
 * @author <a href="mailto:fjuma@redhat.com">Farah Juma</a>
 */
class OTPUtil {

    public static final int[] DELIMS = new int[] {'\n', '\r', '\t', ' '};
    public static final String[] RESPONSE_TYPES = new String[] { WORD_RESPONSE, INIT_WORD_RESPONSE, HEX_RESPONSE, INIT_HEX_RESPONSE };
    public static final String[] PASSWORD_FORMAT_TYPES = new String[] { PASS_PHRASE, DIRECT_OTP };

    private static final int FOUR_LETTER_WORDS_OFFSET = 571;
    private static final byte[] randomCharDictionary;
    static {
        byte[] dict = new byte[36];
        int i = 0;
        for (byte c = 'a'; c <= 'z'; c ++) {
            dict[i ++] = c;
        }
        for (byte c = '0'; c <= '9'; c ++) {
            dict[i ++] = c;
        }
        assert i == dict.length;
        randomCharDictionary = dict;
    }

    /**
     * Pass the given input through a hash function and fold the result to 64 bits.
     *
     * @param algorithm the OTP algorithm, must be either "otp-md5" or "otp-sha1"
     * @param messageDigest the {@code MessageDigest} to use when generating the hash
     * @param input the data to hash
     * @return the folded hash
     */
    public static byte[] hashAndFold(String algorithm, MessageDigest messageDigest, byte[] input) {
        messageDigest.update(input);
        byte[] result = messageDigest.digest();
        byte[] hash = new byte[OTP_HASH_SIZE];

        // Fold the result (either 128 bits for MD5 or 160 bits for SHA-1) to 64 bits
        for (int i = OTP_HASH_SIZE; i < result.length; i++) {
            result[i % OTP_HASH_SIZE] ^= result[i];
        }
        System.arraycopy(result, 0, hash, 0, OTP_HASH_SIZE);

        if (algorithm.equals(ALGORITHM_OTP_SHA1)) {
            reverse(hash, 0, 4);
            reverse(hash, 4, 4);
        }
        return hash;
    }

    /**
     * Pass the given input through a hash function and fold the result to 64 bits.
     *
     * @param algorithm the OTP algorithm
     * @param input the data to hash
     * @return the folded hash
     * @throws SaslException if the given OTP algorithm is invalid
     */
    public static byte[] hashAndFold(String algorithm, byte[] input) throws SaslException {
        final MessageDigest messageDigest;
        try {
            messageDigest = getMessageDigest(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw log.mechInvalidOTPAlgorithm(algorithm).toSaslException();
        }
        return hashAndFold(algorithm, messageDigest, input);
    }

    /**
     * Convert the given OTP hash into hexadecimal format.
     *
     * @param otp the OTP hash
     * @return the hexadecimal value that corresponds to the given OTP hash
     */
    public static String convertToHex(byte[] otp) {
        return ByteIterator.ofBytes(otp).hexEncode().drainToString();
    }

    /**
     * Convert the given hexadecimal OTP value into a hash.
     *
     * @param otp the OTP in hexadecimal format
     * @return the OTP hash that corresponds to the given hexadecimal value
     * @throws SaslException if an error occurs while parsing the hexadecimal value
     */
    public static byte[] convertFromHex(String otp) throws SaslException {
        final CodePointIterator cpi = CodePointIterator.ofString(otp);
        final CodePointIterator di = cpi.delimitedBy(DELIMS);
        // Remove any white space
        final StringBuilder sb = new StringBuilder();
        while (di.hasNext()) {
            di.drainTo(sb);
            skipDelims(di, cpi);
        }
        return CodePointIterator.ofString(sb.toString()).hexDecode().drain();
    }

    /**
     * Convert the given OTP hash into a sequence of six words.
     *
     * @param otp the OTP hash
     * @param alternateDictionary the alternate dictionary to use (if {@code null}, the standard OTP dictionary will be used)
     * @return the sequence of six words that corresponds to the given OTP hash
     */
    public static String convertToWords(byte[] otp, String[] alternateDictionary) {
        final StringBuilder words = new StringBuilder();
        long otpValue = eightBytesToLong(otp);
        for (int i = 0; i < 6; i++) {
           words.append(getWord(otpValue, i, alternateDictionary));
           if (i != 5) {
               words.append(' ');
           }
        }
        return words.toString();
    }

    /**
     * Convert the given OTP hash into the specified format.
     *
     * @param otp the OTP hash
     * @param responseType the response type
     * @param alternateDictionary the alternate dictionary to use (if {@code null}, the standard OTP dictionary will be used)
     * @return the formatted OTP
     * @throws SaslException if the response type is invalid
     */
    public static String formatOTP(byte[] otp, String responseType, String[] alternateDictionary) throws SaslException {
        switch (responseType) {
            case HEX_RESPONSE:
            case INIT_HEX_RESPONSE: {
                return convertToHex(otp);
            }
            case WORD_RESPONSE:
            case INIT_WORD_RESPONSE: {
                return convertToWords(otp, alternateDictionary);
            }
            default:
                throw log.mechInvalidOTPResponseType().toSaslException();
        }
    }

    /**
     * Convert the given six words into an OTP hash.
     *
     * @param words the OTP formatted as a sequence of six words
     * @param algorithm the OTP algorithm
     * @return the OTP hash that corresponds to the given sequence of six words
     * @throws SaslException if the given algorithm is invalid or if the parity encoded in the
     * last two bits of the final word is incorrect or if an error occurs while parsing the words
     */
    public static byte[] convertFromWords(String words, String algorithm) throws SaslException {
        final CodePointIterator cpi = CodePointIterator.ofString(words);
        final CodePointIterator di = cpi.delimitedBy(DELIMS);
        final MessageDigest messageDigest;
        int dictionaryIndex = -1;
        long otpValue = 0L;
        int parity;
        String word;
        byte[] result;
        boolean useStandardDictionary = true;
        try {
            messageDigest = getMessageDigest(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw log.mechInvalidOTPAlgorithm(algorithm).toSaslException();
        }
        for (int i = 0; i < 6; i++) {
            word = di.drainToString();
            if (useStandardDictionary) {
                dictionaryIndex = searchStandardDictionary(word);
                if (dictionaryIndex < 0) {
                    if (i == 0) {
                        // The first word could not be found, switch to using an alternate dictionary instead
                        useStandardDictionary = false;
                    } else {
                        throw log.mechInvalidOTP().toSaslException();
                    }
                }
            }
            if (! useStandardDictionary) {
                // Alternate dictionaries must satisfy the following property:
                // alg(word) % 2048 = N (where N is the position of the word in the dictionary)
                result = hashAndFold(algorithm, messageDigest, word.getBytes(StandardCharsets.UTF_8));
                dictionaryIndex = ((result[result.length - 2] & 0x7) << 8) | (result[result.length - 1] & 0xff);
                messageDigest.reset();
            }
            skipDelims(di, cpi);
            if (i < 5) {
                otpValue |= (((long) dictionaryIndex) << (53 - (11 * i)));
            } else {
                otpValue |= ((((long) dictionaryIndex) & 0x7fc) >> 2);
                parity = dictionaryIndex & 0x3;
                if (parity != calculateParity(otpValue)) {
                    throw log.mechIncorrectParity(SaslMechanismInformation.Names.OTP).toSaslException();
                }
            }
        }
        return longToEightBytes(otpValue);
    }

    /**
     * Search the standard OTP dictionary for the given word.
     *
     * @param word the word to search for
     * @return the index of the word if it is found and a value less than 0 otherwise
     */
    private static int searchStandardDictionary(String word) {
        if (word.length() < 4) {
            return Arrays.binarySearch(STANDARD_DICTIONARY, 0, FOUR_LETTER_WORDS_OFFSET, word.toUpperCase(Locale.ENGLISH));
        } else {
            return Arrays.binarySearch(STANDARD_DICTIONARY, FOUR_LETTER_WORDS_OFFSET, STANDARD_DICTIONARY.length, word.toUpperCase(Locale.ENGLISH));
        }
    }

    /**
     * Get the word from the dictionary that corresponds to the given index for
     * the given OTP hash.
     *
     * @param otp the OTP hash
     * @param index the index of the word to obtain, must be between 0 and 5 (inclusive)
     * @param alternateDictionary the alternate dictionary to use (if {@code null}, the standard OTP dictionary will be used)
     * @return the word that corresponds to the given index for the given OTP hash
     */
    private static String getWord(long otp, int index, String[] alternateDictionary) {
        int dictionaryIndex;
        if ((index >= 0) && (index < 5)) {
            dictionaryIndex = (int) ((otp >> (53 - (11 * index))) & 0x7ff);
        } else if (index == 5) {
            dictionaryIndex = (int) ((((otp << 2) & 0x7fc) | calculateParity(otp)) & 0x7ff);
        } else {
            throw new IllegalArgumentException();
        }
        if (alternateDictionary != null) {
            return alternateDictionary[dictionaryIndex];
        } else {
            return STANDARD_DICTIONARY[dictionaryIndex];
        }
    }

    /**
     * Break down the given hash into pairs of bits and then calculate the sum of the pairs.
     *
     * @param hash the hash
     * @return the two least significant bits of the sum of the pairs of bits from the given hash
     */
    private static int calculateParity(long hash) {
        int parity = 0;
        for (int i = 0; i < 64; i += 2) {
            parity += (hash & 0x3);
            hash >>= 2;
        }
        return (parity & 0x3);
    }

    public static int getResponseTypeChoiceIndex(String responseType) throws SaslException {
        switch (responseType) {
            case WORD_RESPONSE:
                return 0;
            case INIT_WORD_RESPONSE:
                return 1;
            case HEX_RESPONSE:
                return 2;
            case INIT_HEX_RESPONSE:
                return 3;
            default:
                throw log.mechInvalidOTPResponseType().toSaslException();
        }
    }

    public static int getPasswordFormatTypeChoiceIndex(String passwordFormatType) throws SaslException {
        switch (passwordFormatType) {
            case PASS_PHRASE:
                return 0;
            case DIRECT_OTP:
                return 1;
            default:
                throw log.mechInvalidOTPPasswordFormatType().toSaslException();
        }
    }

    public static MessageDigest getMessageDigest(String algorithm) throws NoSuchAlgorithmException {
        switch (algorithm) {
            case ALGORITHM_OTP_MD5:
                return MessageDigest.getInstance("MD5");
            case ALGORITHM_OTP_SHA1:
                return MessageDigest.getInstance("SHA-1");
            case ALGORITHM_OTP_SHA_256:
                return MessageDigest.getInstance("SHA-256");
            case ALGORITHM_OTP_SHA_384:
                return MessageDigest.getInstance("SHA-384");
            case ALGORITHM_OTP_SHA_512:
                return MessageDigest.getInstance("SHA-512");
            default:
                throw new NoSuchAlgorithmException();
        }
    }

    public static String messageDigestAlgorithm(String algorithm) throws NoSuchAlgorithmException {
        switch (algorithm) {
            case ALGORITHM_OTP_MD5:
                return MD5;
            case ALGORITHM_OTP_SHA1:
                return SHA1;
            case ALGORITHM_OTP_SHA_256:
                return SHA256;
            case ALGORITHM_OTP_SHA_384:
                return SHA384;
            case ALGORITHM_OTP_SHA_512:
                return SHA512;
            default:
                throw new NoSuchAlgorithmException();
        }
    }

    public static void validateAlternateDictionary(String[] dictionary) throws SaslException {
        if ((dictionary == null) || (dictionary.length != DICTIONARY_SIZE)) {
            throw log.mechInvalidOTPAlternateDictionary().toSaslException();
        }
        for (int i = 0; i < dictionary.length; i++) {
            if (searchStandardDictionary(dictionary[i]) >= 0) {
                throw log.mechInvalidOTPAlternateDictionary().toSaslException();
            }
        }
    }

    public static void validateUserName(String userName) throws SaslException {
        if (userName == null) {
            throw log.mechNoLoginNameGiven(SaslMechanismInformation.Names.OTP).toSaslException();
        } else if (userName.length() > MAX_AUTHENTICATION_ID_LENGTH) {
            throw log.mechAuthenticationNameTooLong(SaslMechanismInformation.Names.OTP).toSaslException();
        } else if (userName.isEmpty()) {
            throw log.mechAuthenticationNameIsEmpty(SaslMechanismInformation.Names.OTP).toSaslException();
        }
    }

    public static void validateAuthorizationId(String authorizationId) throws SaslException {
        if ((authorizationId != null) && (authorizationId.length() > MAX_AUTHORIZATION_ID_LENGTH)) {
            throw log.mechAuthorizationIdTooLong(SaslMechanismInformation.Names.OTP).toSaslException();
        }
    }

    public static void validateAlgorithm(String algorithm) throws SaslException {
        switch (algorithm) {
            case ALGORITHM_OTP_MD5:
            case ALGORITHM_OTP_SHA1:
            case ALGORITHM_OTP_SHA_256:
            case ALGORITHM_OTP_SHA_384:
            case ALGORITHM_OTP_SHA_512:
                return;
            default:
                throw log.mechInvalidOTPAlgorithm(algorithm).toSaslException();
        }
    }

    public static void validateSequenceNumber(int sequenceNumber) throws SaslException {
        if (sequenceNumber < 1) {
            throw log.mechInvalidOTPSequenceNumber().toSaslException();
        }
    }

    public static void validateSeed(String seed) throws SaslException {
        if ((seed.length() < MIN_SEED_LENGTH) || (seed.length() > MAX_SEED_LENGTH)) {
            throw log.mechInvalidOTPSeed().toSaslException();
        }
        // Must only contain alphanumeric characters
        for (int i = 0; i < seed.length(); i++) {
            if (! Character.isLetterOrDigit(seed.charAt(i))) {
                throw log.mechInvalidCharacterInSeed(SaslMechanismInformation.Names.OTP).toSaslException();
            }
        }
    }

    public static void validatePassPhrase(String passPhrase) throws SaslException {
        if ((passPhrase == null) || (passPhrase.length() < MIN_PASS_PHRASE_LENGTH)
                || (passPhrase.length() > MAX_PASS_PHRASE_LENGTH)) {
            throw log.mechInvalidOTPPassPhrase().toSaslException();
        }
    }

    public static String generateRandomAlphanumericString(int length, Random random) {
        final byte[] chars = new byte[length];
        for (int i = 0; i < length; i ++) {
            chars[i] = randomCharDictionary[random.nextInt(36)];
        }
        return new String(chars, StandardCharsets.US_ASCII);
    }

    public static void skipDelims(CodePointIterator di, CodePointIterator cpi, int...delims) throws SaslException {
        while ((! di.hasNext()) && cpi.hasNext()) {
            if (! isDelim(cpi.next(), delims)) {
                throw log.mechInvalidMessageReceived(SaslMechanismInformation.Names.OTP).toSaslException();
            }
        }
    }

    public static void skipDelims(CodePointIterator di, CodePointIterator cpi) throws SaslException {
        skipDelims(di, cpi, DELIMS);
    }

    private static boolean isDelim(int c, int... delims) {
        for (int delim : delims) {
            if (delim == c) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDelim(int c) {
        return isDelim(c, DELIMS);
    }

    private static void reverse(byte[] bytes, int offset, int length) {
        byte tmp;
        int mid = (length / 2) + offset;
        for (int i = offset, j = offset + length - 1; i < mid; i++, j--) {
            tmp = bytes[i];
            bytes[i] = bytes[j];
            bytes[j] = tmp;
        }
    }

    private static long eightBytesToLong(final byte[] b) {
        long value = 0L;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (b[i] & 0xff);
        }
        return value;
    }

    private static byte[] longToEightBytes(final long value) {
        byte[] b = new byte[8];
        int shift = 56;
        for (int i = 0; i < 8; i++) {
            b[i] = (byte) ((value >>> shift) & 0xff);
            shift -= 8;
        }
        return b;
    }

    // The standard OTP dictionary as defined in RFC 1760 (https://tools.ietf.org/html/rfc1760)
    private static final String[] STANDARD_DICTIONARY =
            new String[] {"A",     "ABE",   "ACE",   "ACT",   "AD",    "ADA",  "ADD",
        "AGO",   "AID",   "AIM",   "AIR",   "ALL",   "ALP",   "AM",    "AMY",
        "AN",    "ANA",   "AND",   "ANN",   "ANT",   "ANY",   "APE",   "APS",
        "APT",   "ARC",   "ARE",   "ARK",   "ARM",   "ART",   "AS",    "ASH",
        "ASK",   "AT",    "ATE",   "AUG",   "AUK",   "AVE",   "AWE",   "AWK",
        "AWL",   "AWN",   "AX",    "AYE",   "BAD",   "BAG",   "BAH",   "BAM",
        "BAN",   "BAR",   "BAT",   "BAY",   "BE",    "BED",   "BEE",   "BEG",
        "BEN",   "BET",   "BEY",   "BIB",   "BID",   "BIG",   "BIN",   "BIT",
        "BOB",   "BOG",   "BON",   "BOO",   "BOP",   "BOW",   "BOY",   "BUB",
        "BUD",   "BUG",   "BUM",   "BUN",   "BUS",   "BUT",   "BUY",   "BY",
        "BYE",   "CAB",   "CAL",   "CAM",   "CAN",   "CAP",   "CAR",   "CAT",
        "CAW",   "COD",   "COG",   "COL",   "CON",   "COO",   "COP",   "COT",
        "COW",   "COY",   "CRY",   "CUB",   "CUE",   "CUP",   "CUR",   "CUT",
        "DAB",   "DAD",   "DAM",   "DAN",   "DAR",   "DAY",   "DEE",   "DEL",
        "DEN",   "DES",   "DEW",   "DID",   "DIE",   "DIG",   "DIN",   "DIP",
        "DO",    "DOE",   "DOG",   "DON",   "DOT",   "DOW",   "DRY",   "DUB",
        "DUD",   "DUE",   "DUG",   "DUN",   "EAR",   "EAT",   "ED",    "EEL",
        "EGG",   "EGO",   "ELI",   "ELK",   "ELM",   "ELY",   "EM",    "END",
        "EST",   "ETC",   "EVA",   "EVE",   "EWE",   "EYE",   "FAD",   "FAN",
        "FAR",   "FAT",   "FAY",   "FED",   "FEE",   "FEW",   "FIB",   "FIG",
        "FIN",   "FIR",   "FIT",   "FLO",   "FLY",   "FOE",   "FOG",   "FOR",
        "FRY",   "FUM",   "FUN",   "FUR",   "GAB",   "GAD",   "GAG",   "GAL",
        "GAM",   "GAP",   "GAS",   "GAY",   "GEE",   "GEL",   "GEM",   "GET",
        "GIG",   "GIL",   "GIN",   "GO",    "GOT",   "GUM",   "GUN",   "GUS",
        "GUT",   "GUY",   "GYM",   "GYP",   "HA",    "HAD",   "HAL",   "HAM",
        "HAN",   "HAP",   "HAS",   "HAT",   "HAW",   "HAY",   "HE",    "HEM",
        "HEN",   "HER",   "HEW",   "HEY",   "HI",    "HID",   "HIM",   "HIP",
        "HIS",   "HIT",   "HO",    "HOB",   "HOC",   "HOE",   "HOG",   "HOP",
        "HOT",   "HOW",   "HUB",   "HUE",   "HUG",   "HUH",   "HUM",   "HUT",
        "I",     "ICY",   "IDA",   "IF",    "IKE",   "ILL",   "INK",   "INN",
        "IO",    "ION",   "IQ",    "IRA",   "IRE",   "IRK",   "IS",    "IT",
        "ITS",   "IVY",   "JAB",   "JAG",   "JAM",   "JAN",   "JAR",   "JAW",
        "JAY",   "JET",   "JIG",   "JIM",   "JO",    "JOB",   "JOE",   "JOG",
        "JOT",   "JOY",   "JUG",   "JUT",   "KAY",   "KEG",   "KEN",   "KEY",
        "KID",   "KIM",   "KIN",   "KIT",   "LA",    "LAB",   "LAC",   "LAD",
        "LAG",   "LAM",   "LAP",   "LAW",   "LAY",   "LEA",   "LED",   "LEE",
        "LEG",   "LEN",   "LEO",   "LET",   "LEW",   "LID",   "LIE",   "LIN",
        "LIP",   "LIT",   "LO",    "LOB",   "LOG",   "LOP",   "LOS",   "LOT",
        "LOU",   "LOW",   "LOY",   "LUG",   "LYE",   "MA",    "MAC",   "MAD",
        "MAE",   "MAN",   "MAO",   "MAP",   "MAT",   "MAW",   "MAY",   "ME",
        "MEG",   "MEL",   "MEN",   "MET",   "MEW",   "MID",   "MIN",   "MIT",
        "MOB",   "MOD",   "MOE",   "MOO",   "MOP",   "MOS",   "MOT",   "MOW",
        "MUD",   "MUG",   "MUM",   "MY",    "NAB",   "NAG",   "NAN",   "NAP",
        "NAT",   "NAY",   "NE",    "NED",   "NEE",   "NET",   "NEW",   "NIB",
        "NIL",   "NIP",   "NIT",   "NO",    "NOB",   "NOD",   "NON",   "NOR",
        "NOT",   "NOV",   "NOW",   "NU",    "NUN",   "NUT",   "O",     "OAF",
        "OAK",   "OAR",   "OAT",   "ODD",   "ODE",   "OF",    "OFF",   "OFT",
        "OH",    "OIL",   "OK",    "OLD",   "ON",    "ONE",   "OR",    "ORB",
        "ORE",   "ORR",   "OS",    "OTT",   "OUR",   "OUT",   "OVA",   "OW",
        "OWE",   "OWL",   "OWN",   "OX",    "PA",    "PAD",   "PAL",   "PAM",
        "PAN",   "PAP",   "PAR",   "PAT",   "PAW",   "PAY",   "PEA",   "PEG",
        "PEN",   "PEP",   "PER",   "PET",   "PEW",   "PHI",   "PI",    "PIE",
        "PIN",   "PIT",   "PLY",   "PO",    "POD",   "POE",   "POP",   "POT",
        "POW",   "PRO",   "PRY",   "PUB",   "PUG",   "PUN",   "PUP",   "PUT",
        "QUO",   "RAG",   "RAM",   "RAN",   "RAP",   "RAT",   "RAW",   "RAY",
        "REB",   "RED",   "REP",   "RET",   "RIB",   "RID",   "RIG",   "RIM",
        "RIO",   "RIP",   "ROB",   "ROD",   "ROE",   "RON",   "ROT",   "ROW",
        "ROY",   "RUB",   "RUE",   "RUG",   "RUM",   "RUN",   "RYE",   "SAC",
        "SAD",   "SAG",   "SAL",   "SAM",   "SAN",   "SAP",   "SAT",   "SAW",
        "SAY",   "SEA",   "SEC",   "SEE",   "SEN",   "SET",   "SEW",   "SHE",
        "SHY",   "SIN",   "SIP",   "SIR",   "SIS",   "SIT",   "SKI",   "SKY",
        "SLY",   "SO",    "SOB",   "SOD",   "SON",   "SOP",   "SOW",   "SOY",
        "SPA",   "SPY",   "SUB",   "SUD",   "SUE",   "SUM",   "SUN",   "SUP",
        "TAB",   "TAD",   "TAG",   "TAN",   "TAP",   "TAR",   "TEA",   "TED",
        "TEE",   "TEN",   "THE",   "THY",   "TIC",   "TIE",   "TIM",   "TIN",
        "TIP",   "TO",    "TOE",   "TOG",   "TOM",   "TON",   "TOO",   "TOP",
        "TOW",   "TOY",   "TRY",   "TUB",   "TUG",   "TUM",   "TUN",   "TWO",
        "UN",    "UP",    "US",    "USE",   "VAN",   "VAT",   "VET",   "VIE",
        "WAD",   "WAG",   "WAR",   "WAS",   "WAY",   "WE",    "WEB",   "WED",
        "WEE",   "WET",   "WHO",   "WHY",   "WIN",   "WIT",   "WOK",   "WON",
        "WOO",   "WOW",   "WRY",   "WU",    "YAM",   "YAP",   "YAW",   "YE",
        "YEA",   "YES",   "YET",   "YOU",   "ABED",  "ABEL",  "ABET",  "ABLE",
        "ABUT",  "ACHE",  "ACID",  "ACME",  "ACRE",  "ACTA",  "ACTS",  "ADAM",
        "ADDS",  "ADEN",  "AFAR",  "AFRO",  "AGEE",  "AHEM",  "AHOY",  "AIDA",
        "AIDE",  "AIDS",  "AIRY",  "AJAR",  "AKIN",  "ALAN",  "ALEC",  "ALGA",
        "ALIA",  "ALLY",  "ALMA",  "ALOE",  "ALSO",  "ALTO",  "ALUM",  "ALVA",
        "AMEN",  "AMES",  "AMID",  "AMMO",  "AMOK",  "AMOS",  "AMRA",  "ANDY",
        "ANEW",  "ANNA",  "ANNE",  "ANTE",  "ANTI",  "AQUA",  "ARAB",  "ARCH",
        "AREA",  "ARGO",  "ARID",  "ARMY",  "ARTS",  "ARTY",  "ASIA",  "ASKS",
        "ATOM",  "AUNT",  "AURA",  "AUTO",  "AVER",  "AVID",  "AVIS",  "AVON",
        "AVOW",  "AWAY",  "AWRY",  "BABE",  "BABY",  "BACH",  "BACK",  "BADE",
        "BAIL",  "BAIT",  "BAKE",  "BALD",  "BALE",  "BALI",  "BALK",  "BALL",
        "BALM",  "BAND",  "BANE",  "BANG",  "BANK",  "BARB",  "BARD",  "BARE",
        "BARK",  "BARN",  "BARR",  "BASE",  "BASH",  "BASK",  "BASS",  "BATE",
        "BATH",  "BAWD",  "BAWL",  "BEAD",  "BEAK",  "BEAM",  "BEAN",  "BEAR",
        "BEAT",  "BEAU",  "BECK",  "BEEF",  "BEEN",  "BEER",  "BEET",  "BELA",
        "BELL",  "BELT",  "BEND",  "BENT",  "BERG",  "BERN",  "BERT",  "BESS",
        "BEST",  "BETA",  "BETH",  "BHOY",  "BIAS",  "BIDE",  "BIEN",  "BILE",
        "BILK",  "BILL",  "BIND",  "BING",  "BIRD",  "BITE",  "BITS",  "BLAB",
        "BLAT",  "BLED",  "BLEW",  "BLOB",  "BLOC",  "BLOT",  "BLOW",  "BLUE",
        "BLUM",  "BLUR",  "BOAR",  "BOAT",  "BOCA",  "BOCK",  "BODE",  "BODY",
        "BOGY",  "BOHR",  "BOIL",  "BOLD",  "BOLO",  "BOLT",  "BOMB",  "BONA",
        "BOND",  "BONE",  "BONG",  "BONN",  "BONY",  "BOOK",  "BOOM",  "BOON",
        "BOOT",  "BORE",  "BORG",  "BORN",  "BOSE",  "BOSS",  "BOTH",  "BOUT",
        "BOWL",  "BOYD",  "BRAD",  "BRAE",  "BRAG",  "BRAN",  "BRAY",  "BRED",
        "BREW",  "BRIG",  "BRIM",  "BROW",  "BUCK",  "BUDD",  "BUFF",  "BULB",
        "BULK",  "BULL",  "BUNK",  "BUNT",  "BUOY",  "BURG",  "BURL",  "BURN",
        "BURR",  "BURT",  "BURY",  "BUSH",  "BUSS",  "BUST",  "BUSY",  "BYTE",
        "CADY",  "CAFE",  "CAGE",  "CAIN",  "CAKE",  "CALF",  "CALL",  "CALM",
        "CAME",  "CANE",  "CANT",  "CARD",  "CARE",  "CARL",  "CARR",  "CART",
        "CASE",  "CASH",  "CASK",  "CAST",  "CAVE",  "CEIL",  "CELL",  "CENT",
        "CERN",  "CHAD",  "CHAR",  "CHAT",  "CHAW",  "CHEF",  "CHEN",  "CHEW",
        "CHIC",  "CHIN",  "CHOU",  "CHOW",  "CHUB",  "CHUG",  "CHUM",  "CITE",
        "CITY",  "CLAD",  "CLAM",  "CLAN",  "CLAW",  "CLAY",  "CLOD",  "CLOG",
        "CLOT",  "CLUB",  "CLUE",  "COAL",  "COAT",  "COCA",  "COCK",  "COCO",
        "CODA",  "CODE",  "CODY",  "COED",  "COIL",  "COIN",  "COKE",  "COLA",
        "COLD",  "COLT",  "COMA",  "COMB",  "COME",  "COOK",  "COOL",  "COON",
        "COOT",  "CORD",  "CORE",  "CORK",  "CORN",  "COST",  "COVE",  "COWL",
        "CRAB",  "CRAG",  "CRAM",  "CRAY",  "CREW",  "CRIB",  "CROW",  "CRUD",
        "CUBA",  "CUBE",  "CUFF",  "CULL",  "CULT",  "CUNY",  "CURB",  "CURD",
        "CURE",  "CURL",  "CURT",  "CUTS",  "DADE",  "DALE",  "DAME",  "DANA",
        "DANE",  "DANG",  "DANK",  "DARE",  "DARK",  "DARN",  "DART",  "DASH",
        "DATA",  "DATE",  "DAVE",  "DAVY",  "DAWN",  "DAYS",  "DEAD",  "DEAF",
        "DEAL",  "DEAN",  "DEAR",  "DEBT",  "DECK",  "DEED",  "DEEM",  "DEER",
        "DEFT",  "DEFY",  "DELL",  "DENT",  "DENY",  "DESK",  "DIAL",  "DICE",
        "DIED",  "DIET",  "DIME",  "DINE",  "DING",  "DINT",  "DIRE",  "DIRT",
        "DISC",  "DISH",  "DISK",  "DIVE",  "DOCK",  "DOES",  "DOLE",  "DOLL",
        "DOLT",  "DOME",  "DONE",  "DOOM",  "DOOR",  "DORA",  "DOSE",  "DOTE",
        "DOUG",  "DOUR",  "DOVE",  "DOWN",  "DRAB",  "DRAG",  "DRAM",  "DRAW",
        "DREW",  "DRUB",  "DRUG",  "DRUM",  "DUAL",  "DUCK",  "DUCT",  "DUEL",
        "DUET",  "DUKE",  "DULL",  "DUMB",  "DUNE",  "DUNK",  "DUSK",  "DUST",
        "DUTY",  "EACH",  "EARL",  "EARN",  "EASE",  "EAST",  "EASY",  "EBEN",
        "ECHO",  "EDDY",  "EDEN",  "EDGE",  "EDGY",  "EDIT",  "EDNA",  "EGAN",
        "ELAN",  "ELBA",  "ELLA",  "ELSE",  "EMIL",  "EMIT",  "EMMA",  "ENDS",
        "ERIC",  "EROS",  "EVEN",  "EVER",  "EVIL",  "EYED",  "FACE",  "FACT",
        "FADE",  "FAIL",  "FAIN",  "FAIR",  "FAKE",  "FALL",  "FAME",  "FANG",
        "FARM",  "FAST",  "FATE",  "FAWN",  "FEAR",  "FEAT",  "FEED",  "FEEL",
        "FEET",  "FELL",  "FELT",  "FEND",  "FERN",  "FEST",  "FEUD",  "FIEF",
        "FIGS",  "FILE",  "FILL",  "FILM",  "FIND",  "FINE",  "FINK",  "FIRE",
        "FIRM",  "FISH",  "FISK",  "FIST",  "FITS",  "FIVE",  "FLAG",  "FLAK",
        "FLAM",  "FLAT",  "FLAW",  "FLEA",  "FLED",  "FLEW",  "FLIT",  "FLOC",
        "FLOG",  "FLOW",  "FLUB",  "FLUE",  "FOAL",  "FOAM",  "FOGY",  "FOIL",
        "FOLD",  "FOLK",  "FOND",  "FONT",  "FOOD",  "FOOL",  "FOOT",  "FORD",
        "FORE",  "FORK",  "FORM",  "FORT",  "FOSS",  "FOUL",  "FOUR",  "FOWL",
        "FRAU",  "FRAY",  "FRED",  "FREE",  "FRET",  "FREY",  "FROG",  "FROM",
        "FUEL",  "FULL",  "FUME",  "FUND",  "FUNK",  "FURY",  "FUSE",  "FUSS",
        "GAFF",  "GAGE",  "GAIL",  "GAIN",  "GAIT",  "GALA",  "GALE",  "GALL",
        "GALT",  "GAME",  "GANG",  "GARB",  "GARY",  "GASH",  "GATE",  "GAUL",
        "GAUR",  "GAVE",  "GAWK",  "GEAR",  "GELD",  "GENE",  "GENT",  "GERM",
        "GETS",  "GIBE",  "GIFT",  "GILD",  "GILL",  "GILT",  "GINA",  "GIRD",
        "GIRL",  "GIST",  "GIVE",  "GLAD",  "GLEE",  "GLEN",  "GLIB",  "GLOB",
        "GLOM",  "GLOW",  "GLUE",  "GLUM",  "GLUT",  "GOAD",  "GOAL",  "GOAT",
        "GOER",  "GOES",  "GOLD",  "GOLF",  "GONE",  "GONG",  "GOOD",  "GOOF",
        "GORE",  "GORY",  "GOSH",  "GOUT",  "GOWN",  "GRAB",  "GRAD",  "GRAY",
        "GREG",  "GREW",  "GREY",  "GRID",  "GRIM",  "GRIN",  "GRIT",  "GROW",
        "GRUB",  "GULF",  "GULL",  "GUNK",  "GURU",  "GUSH",  "GUST",  "GWEN",
        "GWYN",  "HAAG",  "HAAS",  "HACK",  "HAIL",  "HAIR",  "HALE",  "HALF",
        "HALL",  "HALO",  "HALT",  "HAND",  "HANG",  "HANK",  "HANS",  "HARD",
        "HARK",  "HARM",  "HART",  "HASH",  "HAST",  "HATE",  "HATH",  "HAUL",
        "HAVE",  "HAWK",  "HAYS",  "HEAD",  "HEAL",  "HEAR",  "HEAT",  "HEBE",
        "HECK",  "HEED",  "HEEL",  "HEFT",  "HELD",  "HELL",  "HELM",  "HERB",
        "HERD",  "HERE",  "HERO",  "HERS",  "HESS",  "HEWN",  "HICK",  "HIDE",
        "HIGH",  "HIKE",  "HILL",  "HILT",  "HIND",  "HINT",  "HIRE",  "HISS",
        "HIVE",  "HOBO",  "HOCK",  "HOFF",  "HOLD",  "HOLE",  "HOLM",  "HOLT",
        "HOME",  "HONE",  "HONK",  "HOOD",  "HOOF",  "HOOK",  "HOOT",  "HORN",
        "HOSE",  "HOST",  "HOUR",  "HOVE",  "HOWE",  "HOWL",  "HOYT",  "HUCK",
        "HUED",  "HUFF",  "HUGE",  "HUGH",  "HUGO",  "HULK",  "HULL",  "HUNK",
        "HUNT",  "HURD",  "HURL",  "HURT",  "HUSH",  "HYDE",  "HYMN",  "IBIS",
        "ICON",  "IDEA",  "IDLE",  "IFFY",  "INCA",  "INCH",  "INTO",  "IONS",
        "IOTA",  "IOWA",  "IRIS",  "IRMA",  "IRON",  "ISLE",  "ITCH",  "ITEM",
        "IVAN",  "JACK",  "JADE",  "JAIL",  "JAKE",  "JANE",  "JAVA",  "JEAN",
        "JEFF",  "JERK",  "JESS",  "JEST",  "JIBE",  "JILL",  "JILT",  "JIVE",
        "JOAN",  "JOBS",  "JOCK",  "JOEL",  "JOEY",  "JOHN",  "JOIN",  "JOKE",
        "JOLT",  "JOVE",  "JUDD",  "JUDE",  "JUDO",  "JUDY",  "JUJU",  "JUKE",
        "JULY",  "JUNE",  "JUNK",  "JUNO",  "JURY",  "JUST",  "JUTE",  "KAHN",
        "KALE",  "KANE",  "KANT",  "KARL",  "KATE",  "KEEL",  "KEEN",  "KENO",
        "KENT",  "KERN",  "KERR",  "KEYS",  "KICK",  "KILL",  "KIND",  "KING",
        "KIRK",  "KISS",  "KITE",  "KLAN",  "KNEE",  "KNEW",  "KNIT",  "KNOB",
        "KNOT",  "KNOW",  "KOCH",  "KONG",  "KUDO",  "KURD",  "KURT",  "KYLE",
        "LACE",  "LACK",  "LACY",  "LADY",  "LAID",  "LAIN",  "LAIR",  "LAKE",
        "LAMB",  "LAME",  "LAND",  "LANE",  "LANG",  "LARD",  "LARK",  "LASS",
        "LAST",  "LATE",  "LAUD",  "LAVA",  "LAWN",  "LAWS",  "LAYS",  "LEAD",
        "LEAF",  "LEAK",  "LEAN",  "LEAR",  "LEEK",  "LEER",  "LEFT",  "LEND",
        "LENS",  "LENT",  "LEON",  "LESK",  "LESS",  "LEST",  "LETS",  "LIAR",
        "LICE",  "LICK",  "LIED",  "LIEN",  "LIES",  "LIEU",  "LIFE",  "LIFT",
        "LIKE",  "LILA",  "LILT",  "LILY",  "LIMA",  "LIMB",  "LIME",  "LIND",
        "LINE",  "LINK",  "LINT",  "LION",  "LISA",  "LIST",  "LIVE",  "LOAD",
        "LOAF",  "LOAM",  "LOAN",  "LOCK",  "LOFT",  "LOGE",  "LOIS",  "LOLA",
        "LONE",  "LONG",  "LOOK",  "LOON",  "LOOT",  "LORD",  "LORE",  "LOSE",
        "LOSS",  "LOST",  "LOUD",  "LOVE",  "LOWE",  "LUCK",  "LUCY",  "LUGE",
        "LUKE",  "LULU",  "LUND",  "LUNG",  "LURA",  "LURE",  "LURK",  "LUSH",
        "LUST",  "LYLE",  "LYNN",  "LYON",  "LYRA",  "MACE",  "MADE",  "MAGI",
        "MAID",  "MAIL",  "MAIN",  "MAKE",  "MALE",  "MALI",  "MALL",  "MALT",
        "MANA",  "MANN",  "MANY",  "MARC",  "MARE",  "MARK",  "MARS",  "MART",
        "MARY",  "MASH",  "MASK",  "MASS",  "MAST",  "MATE",  "MATH",  "MAUL",
        "MAYO",  "MEAD",  "MEAL",  "MEAN",  "MEAT",  "MEEK",  "MEET",  "MELD",
        "MELT",  "MEMO",  "MEND",  "MENU",  "MERT",  "MESH",  "MESS",  "MICE",
        "MIKE",  "MILD",  "MILE",  "MILK",  "MILL",  "MILT",  "MIMI",  "MIND",
        "MINE",  "MINI",  "MINK",  "MINT",  "MIRE",  "MISS",  "MIST",  "MITE",
        "MITT",  "MOAN",  "MOAT",  "MOCK",  "MODE",  "MOLD",  "MOLE",  "MOLL",
        "MOLT",  "MONA",  "MONK",  "MONT",  "MOOD",  "MOON",  "MOOR",  "MOOT",
        "MORE",  "MORN",  "MORT",  "MOSS",  "MOST",  "MOTH",  "MOVE",  "MUCH",
        "MUCK",  "MUDD",  "MUFF",  "MULE",  "MULL",  "MURK",  "MUSH",  "MUST",
        "MUTE",  "MUTT",  "MYRA",  "MYTH",  "NAGY",  "NAIL",  "NAIR",  "NAME",
        "NARY",  "NASH",  "NAVE",  "NAVY",  "NEAL",  "NEAR",  "NEAT",  "NECK",
        "NEED",  "NEIL",  "NELL",  "NEON",  "NERO",  "NESS",  "NEST",  "NEWS",
        "NEWT",  "NIBS",  "NICE",  "NICK",  "NILE",  "NINA",  "NINE",  "NOAH",
        "NODE",  "NOEL",  "NOLL",  "NONE",  "NOOK",  "NOON",  "NORM",  "NOSE",
        "NOTE",  "NOUN",  "NOVA",  "NUDE",  "NULL",  "NUMB",  "OATH",  "OBEY",
        "OBOE",  "ODIN",  "OHIO",  "OILY",  "OINT",  "OKAY",  "OLAF",  "OLDY",
        "OLGA",  "OLIN",  "OMAN",  "OMEN",  "OMIT",  "ONCE",  "ONES",  "ONLY",
        "ONTO",  "ONUS",  "ORAL",  "ORGY",  "OSLO",  "OTIS",  "OTTO",  "OUCH",
        "OUST",  "OUTS",  "OVAL",  "OVEN",  "OVER",  "OWLY",  "OWNS",  "QUAD",
        "QUIT",  "QUOD",  "RACE",  "RACK",  "RACY",  "RAFT",  "RAGE",  "RAID",
        "RAIL",  "RAIN",  "RAKE",  "RANK",  "RANT",  "RARE",  "RASH",  "RATE",
        "RAVE",  "RAYS",  "READ",  "REAL",  "REAM",  "REAR",  "RECK",  "REED",
        "REEF",  "REEK",  "REEL",  "REID",  "REIN",  "RENA",  "REND",  "RENT",
        "REST",  "RICE",  "RICH",  "RICK",  "RIDE",  "RIFT",  "RILL",  "RIME",
        "RING",  "RINK",  "RISE",  "RISK",  "RITE",  "ROAD",  "ROAM",  "ROAR",
        "ROBE",  "ROCK",  "RODE",  "ROIL",  "ROLL",  "ROME",  "ROOD",  "ROOF",
        "ROOK",  "ROOM",  "ROOT",  "ROSA",  "ROSE",  "ROSS",  "ROSY",  "ROTH",
        "ROUT",  "ROVE",  "ROWE",  "ROWS",  "RUBE",  "RUBY",  "RUDE",  "RUDY",
        "RUIN",  "RULE",  "RUNG",  "RUNS",  "RUNT",  "RUSE",  "RUSH",  "RUSK",
        "RUSS",  "RUST",  "RUTH",  "SACK",  "SAFE",  "SAGE",  "SAID",  "SAIL",
        "SALE",  "SALK",  "SALT",  "SAME",  "SAND",  "SANE",  "SANG",  "SANK",
        "SARA",  "SAUL",  "SAVE",  "SAYS",  "SCAN",  "SCAR",  "SCAT",  "SCOT",
        "SEAL",  "SEAM",  "SEAR",  "SEAT",  "SEED",  "SEEK",  "SEEM",  "SEEN",
        "SEES",  "SELF",  "SELL",  "SEND",  "SENT",  "SETS",  "SEWN",  "SHAG",
        "SHAM",  "SHAW",  "SHAY",  "SHED",  "SHIM",  "SHIN",  "SHOD",  "SHOE",
        "SHOT",  "SHOW",  "SHUN",  "SHUT",  "SICK",  "SIDE",  "SIFT",  "SIGH",
        "SIGN",  "SILK",  "SILL",  "SILO",  "SILT",  "SINE",  "SING",  "SINK",
        "SIRE",  "SITE",  "SITS",  "SITU",  "SKAT",  "SKEW",  "SKID",  "SKIM",
        "SKIN",  "SKIT",  "SLAB",  "SLAM",  "SLAT",  "SLAY",  "SLED",  "SLEW",
        "SLID",  "SLIM",  "SLIT",  "SLOB",  "SLOG",  "SLOT",  "SLOW",  "SLUG",
        "SLUM",  "SLUR",  "SMOG",  "SMUG",  "SNAG",  "SNOB",  "SNOW",  "SNUB",
        "SNUG",  "SOAK",  "SOAR",  "SOCK",  "SODA",  "SOFA",  "SOFT",  "SOIL",
        "SOLD",  "SOME",  "SONG",  "SOON",  "SOOT",  "SORE",  "SORT",  "SOUL",
        "SOUR",  "SOWN",  "STAB",  "STAG",  "STAN",  "STAR",  "STAY",  "STEM",
        "STEW",  "STIR",  "STOW",  "STUB",  "STUN",  "SUCH",  "SUDS",  "SUIT",
        "SULK",  "SUMS",  "SUNG",  "SUNK",  "SURE",  "SURF",  "SWAB",  "SWAG",
        "SWAM",  "SWAN",  "SWAT",  "SWAY",  "SWIM",  "SWUM",  "TACK",  "TACT",
        "TAIL",  "TAKE",  "TALE",  "TALK",  "TALL",  "TANK",  "TASK",  "TATE",
        "TAUT",  "TEAL",  "TEAM",  "TEAR",  "TECH",  "TEEM",  "TEEN",  "TEET",
        "TELL",  "TEND",  "TENT",  "TERM",  "TERN",  "TESS",  "TEST",  "THAN",
        "THAT",  "THEE",  "THEM",  "THEN",  "THEY",  "THIN",  "THIS",  "THUD",
        "THUG",  "TICK",  "TIDE",  "TIDY",  "TIED",  "TIER",  "TILE",  "TILL",
        "TILT",  "TIME",  "TINA",  "TINE",  "TINT",  "TINY",  "TIRE",  "TOAD",
        "TOGO",  "TOIL",  "TOLD",  "TOLL",  "TONE",  "TONG",  "TONY",  "TOOK",
        "TOOL",  "TOOT",  "TORE",  "TORN",  "TOTE",  "TOUR",  "TOUT",  "TOWN",
        "TRAG",  "TRAM",  "TRAY",  "TREE",  "TREK",  "TRIG",  "TRIM",  "TRIO",
        "TROD",  "TROT",  "TROY",  "TRUE",  "TUBA",  "TUBE",  "TUCK",  "TUFT",
        "TUNA",  "TUNE",  "TUNG",  "TURF",  "TURN",  "TUSK",  "TWIG",  "TWIN",
        "TWIT",  "ULAN",  "UNIT",  "URGE",  "USED",  "USER",  "USES",  "UTAH",
        "VAIL",  "VAIN",  "VALE",  "VARY",  "VASE",  "VAST",  "VEAL",  "VEDA",
        "VEIL",  "VEIN",  "VEND",  "VENT",  "VERB",  "VERY",  "VETO",  "VICE",
        "VIEW",  "VINE",  "VISE",  "VOID",  "VOLT",  "VOTE",  "WACK",  "WADE",
        "WAGE",  "WAIL",  "WAIT",  "WAKE",  "WALE",  "WALK",  "WALL",  "WALT",
        "WAND",  "WANE",  "WANG",  "WANT",  "WARD",  "WARM",  "WARN",  "WART",
        "WASH",  "WAST",  "WATS",  "WATT",  "WAVE",  "WAVY",  "WAYS",  "WEAK",
        "WEAL",  "WEAN",  "WEAR",  "WEED",  "WEEK",  "WEIR",  "WELD",  "WELL",
        "WELT",  "WENT",  "WERE",  "WERT",  "WEST",  "WHAM",  "WHAT",  "WHEE",
        "WHEN",  "WHET",  "WHOA",  "WHOM",  "WICK",  "WIFE",  "WILD",  "WILL",
        "WIND",  "WINE",  "WING",  "WINK",  "WINO",  "WIRE",  "WISE",  "WISH",
        "WITH",  "WOLF",  "WONT",  "WOOD",  "WOOL",  "WORD",  "WORE",  "WORK",
        "WORM",  "WORN",  "WOVE",  "WRIT",  "WYNN",  "YALE",  "YANG",  "YANK",
        "YARD",  "YARN",  "YAWL",  "YAWN",  "YEAH",  "YEAR",  "YELL",  "YOGA",
        "YOKE" };
}