package app.autoturn.vpn;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ProfileBundleTest {
    private static final String LINK = "qwdtt://config?peer=vpn.example%3A56002&hashes=abcdefgh_12345678&pass=fixture-only&name=Router";

    @Test public void roundTripKeepsTypeFlagsAndConfig() throws Exception {
        Profile source = new Profile("id-1", "Reserve", Profile.QWDTT, LINK, true, false);
        String exported = ProfileImporter.exportBundle(Arrays.asList(source));
        List<Profile> imported = ProfileImporter.parseBundle(exported);
        assertEquals(1, imported.size());
        Profile copy = imported.get(0);
        assertEquals("Reserve", copy.name);
        assertEquals(Profile.QWDTT, copy.type);
        assertEquals(LINK, copy.config);
        assertTrue(copy.lastResort);
        assertFalse(copy.enabled);
        assertNotEquals("imported profiles get fresh ids", source.id, copy.id);
    }

    @Test public void duplicatesAreSkipped() {
        Profile existing = new Profile("a", "Old", Profile.QWDTT, LINK, true, true);
        Profile same = new Profile("b", "Copy", Profile.QWDTT, LINK + "  ", true, true);
        Profile other = new Profile("c", "New", Profile.QWDTT, LINK.replace("Router", "Other"), true, true);
        Profile repeated = new Profile("d", "New again", Profile.QWDTT, other.config, true, true);
        List<Profile> fresh = ProfileImporter.withoutDuplicates(Arrays.asList(existing),
                Arrays.asList(same, other, repeated));
        assertEquals(1, fresh.size());
        assertEquals("c", fresh.get(0).id);
    }

    @Test public void ordinaryTextIsNotABundle() throws Exception {
        assertNull(ProfileImporter.parseBundle(LINK));
        assertNull(ProfileImporter.parseBundle("{\"outbounds\":[]}"));
    }

    @Test public void invalidProfileRejectsWholeBundle() {
        String bundle = "{\"autoturnProfiles\":[{\"name\":\"x\",\"config\":\"not a profile\"}]}";
        try { ProfileImporter.parseBundle(bundle); fail(); }
        catch (Exception expected) { /* nothing is imported */ }
    }
}
