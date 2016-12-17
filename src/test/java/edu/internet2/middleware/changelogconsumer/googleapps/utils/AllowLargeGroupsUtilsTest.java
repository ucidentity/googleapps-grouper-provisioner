package edu.internet2.middleware.changelogconsumer.googleapps.utils;

import edu.internet2.middleware.grouper.Group;
import edu.internet2.middleware.grouper.Member;
import edu.internet2.middleware.grouper.attr.AttributeDefName;
import edu.internet2.middleware.grouper.attr.assign.AttributeAssign;
import edu.internet2.middleware.grouper.attr.assign.AttributeAssignGroupDelegate;
import edu.internet2.middleware.grouper.attr.finder.AttributeDefNameFinder;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Matchers;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.mock;


/**
 * Created by jgasper on 12/16/16.
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest(value = { AttributeDefNameFinder.class })
public class AllowLargeGroupsUtilsTest {

    @Before
    public void setUp() throws Exception {
        PowerMockito.mockStatic(AttributeDefNameFinder.class);
        when(AttributeDefNameFinder.findByName(Matchers.anyString(), Matchers.anyBoolean())).thenReturn(mock(AttributeDefName.class));
    }

    @Test
    public void testBlockNewMember() throws Exception {
        GoogleAppsSyncProperties properties = createGoogleAppsSyncProperties(5, "test");
        AllowLargeGroupsUtils algu = new AllowLargeGroupsUtils(properties);

        Group group = CreateTestGroup(4, 1);
        assertFalse("members good and override attribute assigned", algu.blockNewMember(group));

        group = CreateTestGroup(4, 0);
        assertFalse("members good and no override assigned", algu.blockNewMember(group));

        group = CreateTestGroup(6, 1);
        assertFalse("too many members and override attribute assigned", algu.blockNewMember(group));

        group = CreateTestGroup(6, 0);
        assertTrue("members override and no override assigned", algu.blockNewMember(group));
    }


    //Creating objects
    private GoogleAppsSyncProperties createGoogleAppsSyncProperties(int largeGroupSize, String attributeName) {
        GoogleAppsSyncProperties properties = mock(GoogleAppsSyncProperties.class);
        when(properties.getAllowLargeGroupsAttributeDefName()).thenReturn(attributeName);
        when(properties.getLargeGroupSize()).thenReturn(largeGroupSize);

        return properties;
    }

    private Group CreateTestGroup(int memberSize, int attributeAssigns) {
        //Set the number of members returned
        Set<Member> members = mock(Set.class);
        when(members.size()).thenReturn(memberSize);

        //Set the attribute assignment count
        Set<AttributeAssign> attributeAssignSet = mock(Set.class);
        when(attributeAssignSet.size()).thenReturn(attributeAssigns);

        AttributeAssignGroupDelegate attributeAssignGroupDelegate = mock(AttributeAssignGroupDelegate.class);
        when(attributeAssignGroupDelegate.retrieveAssignments(Matchers.any(AttributeDefName.class))).thenReturn(attributeAssignSet);

        //Assign our mocked sub-objects
        Group group = mock(Group.class);
        when(group.getEffectiveMembers()).thenReturn(members);
        when(group.getAttributeDelegate()).thenReturn(attributeAssignGroupDelegate);

        return group;
    }
}