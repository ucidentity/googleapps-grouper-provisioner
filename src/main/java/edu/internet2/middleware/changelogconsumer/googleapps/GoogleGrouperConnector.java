/*******************************************************************************
 * Copyright 2015 Internet2
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package edu.internet2.middleware.changelogconsumer.googleapps;

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.admin.directory.Directory;
import com.google.api.services.admin.directory.model.Group;
import com.google.api.services.admin.directory.model.Member;
import com.google.api.services.admin.directory.model.User;
import com.google.api.services.admin.directory.model.UserName;
import com.google.api.services.groupssettings.Groupssettings;
import com.google.api.services.groupssettings.model.Groups;
import edu.internet2.middleware.changelogconsumer.googleapps.cache.Cache;
import edu.internet2.middleware.changelogconsumer.googleapps.cache.GoogleCacheManager;
import edu.internet2.middleware.changelogconsumer.googleapps.utils.AddressFormatter;
import edu.internet2.middleware.changelogconsumer.googleapps.utils.GoogleAppsSyncProperties;
import edu.internet2.middleware.changelogconsumer.googleapps.utils.RecentlyManipulatedObjectsList;
import edu.internet2.middleware.grouper.*;
import edu.internet2.middleware.grouper.attr.AttributeDef;
import edu.internet2.middleware.grouper.attr.AttributeDefName;
import edu.internet2.middleware.grouper.attr.AttributeDefType;
import edu.internet2.middleware.grouper.attr.AttributeDefValueType;
import edu.internet2.middleware.grouper.attr.assign.AttributeAssign;
import edu.internet2.middleware.grouper.attr.finder.AttributeDefFinder;
import edu.internet2.middleware.grouper.attr.finder.AttributeDefNameFinder;
import edu.internet2.middleware.grouper.filter.*;
import edu.internet2.middleware.grouper.internal.dao.QueryOptions;
import edu.internet2.middleware.grouper.misc.GrouperDAOFactory;
import edu.internet2.middleware.grouper.util.GrouperUtil;
import edu.internet2.middleware.subject.Subject;
import edu.internet2.middleware.subject.provider.SubjectTypeEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.common.collect.Sets;
import org.apache.commons.collections.CollectionUtils;

import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Collection;

/**
 * Contains methods used by both the ChangeLogConsumer and the FullSync classes.
 *
 * @author John Gasper, Unicon
 */
public class GoogleGrouperConnector {
    public static final String SYNC_TO_GOOGLE = "syncToGoogle";
    public static final String GOOGLE_PROVISIONER = "googleProvisioner";
    public static final String ATTRIBUTE_CONFIG_STEM = "etc:attribute:provisioningTargets";
    public static final String GOOGLE_CONFIG_STEM = ATTRIBUTE_CONFIG_STEM + ":" + GOOGLE_PROVISIONER;
    public static final String SYNC_TO_GOOGLE_NAME = GOOGLE_CONFIG_STEM + ":" + SYNC_TO_GOOGLE;

    private static final Logger LOG = LoggerFactory.getLogger(GoogleGrouperConnector.class);
    /** Global instance of the JSON factory. */
    private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();

    private Directory directoryClient;
    private Groupssettings groupssettingsClient;

    //The Google Objects hang around a lot longer due to Google API constraints, so they are stored in a static GoogleCacheManager class.
    //Grouper ones are easier to refresh.
    private Cache<Subject> grouperSubjects;
    private Cache<edu.internet2.middleware.grouper.Group> grouperGroups;
    private HashMap<String, String> syncedObjects;

    private String consumerName;
    private AttributeDefName syncAttribute;
    private String syncAttributeDefName;
    private GoogleAppsSyncProperties properties;
    private AddressFormatter addressFormatter;
    private RecentlyManipulatedObjectsList recentlyManipulatedObjectsList;

	private String attributeForGooUserLookup;
	private boolean appendDomainToGooUserAttribute;
	private String domain;

    public GoogleGrouperConnector() {
        grouperSubjects = new Cache<Subject>();
        grouperGroups = new Cache<edu.internet2.middleware.grouper.Group>();
        syncedObjects = new HashMap<String, String>();
        addressFormatter = new AddressFormatter();
    }

    public void initialize(String consumerName, final GoogleAppsSyncProperties properties) throws GeneralSecurityException, IOException {
        this.consumerName = consumerName;
        this.properties = properties;

        final HttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();

        final GoogleCredential googleDirectoryCredential = GoogleAppsSdkUtils.getGoogleDirectoryCredential(
                properties.getServiceAccountEmail(), properties.getServiceAccountPKCS12FilePath(), properties.getServiceImpersonationUser(),
                httpTransport, JSON_FACTORY);

        final GoogleCredential googleGroupssettingsCredential = GoogleAppsSdkUtils.getGoogleGroupssettingsCredential(
                properties.getServiceAccountEmail(), properties.getServiceAccountPKCS12FilePath(), properties.getServiceImpersonationUser(),
                httpTransport, JSON_FACTORY);

        directoryClient = new Directory.Builder(httpTransport, JSON_FACTORY, googleDirectoryCredential)
                .setApplicationName("Google Apps Grouper Provisioner")
                .build();

        groupssettingsClient = new Groupssettings.Builder(httpTransport, JSON_FACTORY, googleGroupssettingsCredential)
                .setApplicationName("Google Apps Grouper Provisioner")
                .build();

        addressFormatter.setGroupIdentifierExpression(properties.getGroupIdentifierExpression())
                .setSubjectIdentifierExpression(properties.getSubjectIdentifierExpression())
                .setDomain(properties.getGoogleDomain());

        GoogleCacheManager.googleUsers().setCacheValidity(properties.getGoogleUserCacheValidity());
        GoogleCacheManager.googleGroups().setCacheValidity(properties.getGoogleGroupCacheValidity());

        grouperSubjects.setCacheValidity(5);
        grouperSubjects.seed(1000);

        grouperGroups.setCacheValidity(5);
        grouperGroups.seed(100);

        recentlyManipulatedObjectsList = new RecentlyManipulatedObjectsList(properties.getRecentlyManipulatedQueueSize(), properties.getRecentlyManipulatedQueueDelay());

	attributeForGooUserLookup = properties.getAttributeForGooUserLookup();

	appendDomainToGooUserAttribute = properties.getAppendDomainToGooUserAttribute();

	domain = properties.getGoogleDomain();

    }

    /**
     * populates the Google user and group caches.
     */
    public void populateGoogleCache() {
        populateGooUsersCache(directoryClient);
        populateGooGroupsCache(directoryClient, domain);
    }

    public void populateGooUsersCache(Directory directory) {
        LOG.debug("Google Apps Consumer '{}' - Populating the userCache.", consumerName);

        if (GoogleCacheManager.googleUsers().isExpired()) {
            try {
                final List<User> list = GoogleAppsSdkUtils.retrieveAllUsers(directoryClient);
                GoogleCacheManager.googleUsers().seed(list);

            } catch (GoogleJsonResponseException e) {
                LOG.error("Google Apps Consumer '{}' - Something bad happened when populating the userCache: {}", consumerName, e);
            } catch (IOException e) {
                LOG.error("Google Apps Consumer '{}' - Something bad happened when populating the userCache: {}", consumerName, e);
            }
        }
    }

    public void populateGooGroupsCache(Directory directory, String domain) {
        LOG.debug("Google Apps Consumer '{}' - Populating the groupCache.", consumerName);

        if (GoogleCacheManager.googleGroups().isExpired()) {
            try {
                final List<Group> list = GoogleAppsSdkUtils.retrieveAllGroups(directoryClient, domain);
                GoogleCacheManager.googleGroups().seed(list);

            } catch (GoogleJsonResponseException e) {
                LOG.error("Google Apps Consumer '{}' - Something bad happened when populating the groupCache: {}", consumerName, e);
            } catch (IOException e) {
                LOG.error("Google Apps Consumer '{}' - Something bad happened when populating the groupCache: {}", consumerName, e);
            }
        }
    }

    public Group fetchGooGroup(String groupKey) throws IOException {
        Group group = GoogleCacheManager.googleGroups().get(groupKey);
        if (group == null) {
            group = GoogleAppsSdkUtils.retrieveGroup(directoryClient, groupKey);

            if (group != null) {
                GoogleCacheManager.googleGroups().put(group);
            }
        }

        return group;
    }

    public User fetchGooUser(String userKey) {
        if (userKey == null) {
            LOG.warn("userKey is null");
            return null;
        }

        User user = GoogleCacheManager.googleUsers().get(userKey);
        if (user == null) {
            try {
                user = GoogleAppsSdkUtils.retrieveUser(directoryClient, userKey);
            } catch (IOException e) {
                LOG.warn("Google Apps Consume '{}' - Error fetching user ({}) from Google: {}", new Object[]{consumerName, userKey, e.getMessage()});
            }

            if (user != null) {
                GoogleCacheManager.googleUsers().put(user);
            }
        }

        return user;
    }

    public edu.internet2.middleware.grouper.Group fetchGrouperGroup(String groupName) {
        LOG.debug("Google Apps Consumer '{}' - fetchGrouperGroup(), groupName is: {}", consumerName, groupName);
        edu.internet2.middleware.grouper.Group group = grouperGroups.get(groupName);
        if (group == null) {
            group = GroupFinder.findByName(GrouperSession.staticGrouperSession(false), groupName, false);

            if (group != null) {
                grouperGroups.put(group);
            }
        }
        return group;
    }

    public edu.internet2.middleware.grouper.Group fetchGrouperGroupApproximate(String groupName) {
        LOG.debug("Google Apps Consumer '{}' - fetchGrouperGroupApproximate(), groupName is: {}", consumerName, groupName);
        // Find the root stem
        Stem stem = StemFinder.findRootStem(GrouperSession.staticGrouperSession(false));
        // Make the query. Checks current group name, display name, extension, display extension only
        Set<edu.internet2.middleware.grouper.Group> groups =
                GrouperQuery.createQuery(GrouperSession.staticGrouperSession(false), new GroupCurrentNameFilter(groupName, stem)).getGroups();
        // Confirm there was only one group returned. If not create an error report
        if (groups != null) {
            if (groups.size() == 1) {
                return (edu.internet2.middleware.grouper.Group) groups.toArray()[0];
            } else if (groups.size() >= 1) {
                // Since a substring search is used, there may be matches to similar names.
                LOG.warn("More than one group was found {}. Checking further.", groups);
                for (edu.internet2.middleware.grouper.Group group : groups) {
                    if (group.getName().endsWith(":" + groupName)) {
                        LOG.info ("Choosing this group: {}", group.getName());
                        return group;
                    }
                }
            }
        }
        LOG.error("No groups found");
        return null;
    }


    public Subject fetchGrouperSubject(String sourceId, String subjectId) {
        Subject subject = grouperSubjects.get(sourceId + "__" + subjectId);
        if (subject == null) {
            subject = SubjectFinder.findByIdAndSource(subjectId, sourceId, false);

            if (subject != null) {
                grouperSubjects.put(subject);
            }
        }

        return subject;
    }

    public User createGooUser(Subject subject) throws IOException {
        if (subject == null) {
            LOG.warn("subject is null");
            return null;
        }

        final String email = subject.getAttributeValue("email");
        final String subjectName = subject.getName();

        User newUser = null;
        if (properties.shouldProvisionUsers()) {
            newUser = new User();
            newUser.setPassword(new BigInteger(130, new SecureRandom()).toString(32))
                    .setPrimaryEmail(email != null ? email : fetchGooUserIdentifier(subject))
                    .setIncludeInGlobalAddressList(properties.shouldIncludeUserInGlobalAddressList())
                    .setName(new UserName())
                    .getName().setFullName(subjectName);

            if (properties.useSimpleSubjectNaming()) {
                final String[] subjectNameSplit = subjectName.split(" ");
                newUser.getName().setFamilyName(subjectNameSplit[subjectNameSplit.length - 1])
                        .setGivenName(subjectNameSplit[0]);

            } else {
                newUser.getName().setFamilyName(subject.getAttributeValue(properties.getSubjectSurnameField()))
                        .setGivenName(subject.getAttributeValue(properties.getSubjectGivenNameField()));
            }

            newUser = GoogleAppsSdkUtils.addUser(directoryClient, newUser);
            GoogleCacheManager.googleUsers().put(newUser);
        }

        return newUser;
    }

    public void createGooMember(Group group, User user, String role) throws IOException {
        final Member gMember = new Member();
        gMember.setEmail(user.getPrimaryEmail())
                .setRole(role);

        recentlyManipulatedObjectsList.delayIfNeeded(gMember.getEmail());
        GoogleAppsSdkUtils.addGroupMember(directoryClient, group.getEmail(), gMember);
        recentlyManipulatedObjectsList.add(gMember.getEmail());
    }

    public void createGooGroupIfNecessary(edu.internet2.middleware.grouper.Group grouperGroup) throws IOException {
        final String groupKey = addressFormatter.qualifyGroupAddress(grouperGroup.getName());

        Group googleGroup = fetchGooGroup(groupKey);
        recentlyManipulatedObjectsList.delayIfNeeded(groupKey);

        if (googleGroup == null) {
            googleGroup = new Group();
            googleGroup.setName(grouperGroup.getDisplayExtension())
                    .setEmail(groupKey)
                    .setDescription(grouperGroup.getDescription());


            GoogleCacheManager.googleGroups().put(GoogleAppsSdkUtils.addGroup(directoryClient, googleGroup));
            recentlyManipulatedObjectsList.add(groupKey);

            recentlyManipulatedObjectsList.delayIfNeeded(groupKey);
            final Groups groupSettings = GoogleAppsSdkUtils.retrieveGroupSettings(groupssettingsClient, groupKey);
            final Groups defaultGroupSettings = properties.getDefaultGroupSettings();
            groupSettings.setWhoCanViewMembership(defaultGroupSettings.getWhoCanViewMembership())
                    .setWhoCanInvite(defaultGroupSettings.getWhoCanInvite())
					.setWhoCanAdd(defaultGroupSettings.getWhoCanAdd())
					.setWhoCanJoin(defaultGroupSettings.getWhoCanJoin())
					.setWhoCanLeaveGroup(defaultGroupSettings.getWhoCanLeaveGroup())
                    .setAllowExternalMembers(defaultGroupSettings.getAllowExternalMembers())
                    .setWhoCanPostMessage(defaultGroupSettings.getWhoCanPostMessage())
                    .setWhoCanJoin(defaultGroupSettings.getWhoCanJoin())
                    .setAllowWebPosting(defaultGroupSettings.getAllowWebPosting())
                    .setPrimaryLanguage(defaultGroupSettings.getPrimaryLanguage())
                    .setMaxMessageBytes(defaultGroupSettings.getMaxMessageBytes())
                    .setIsArchived(defaultGroupSettings.getIsArchived())
                    .setMessageModerationLevel(defaultGroupSettings.getMessageModerationLevel())
                    .setSpamModerationLevel(defaultGroupSettings.getSpamModerationLevel())
                    .setReplyTo(defaultGroupSettings.getReplyTo())
                    .setCustomReplyTo(defaultGroupSettings.getCustomReplyTo())
                    .setSendMessageDenyNotification(defaultGroupSettings.getSendMessageDenyNotification())
                    .setDefaultMessageDenyNotificationText(defaultGroupSettings.getDefaultMessageDenyNotificationText())
                    .setShowInGroupDirectory(defaultGroupSettings.getShowInGroupDirectory())
                    .setAllowGoogleCommunication(defaultGroupSettings.getAllowGoogleCommunication())
                    .setMembersCanPostAsTheGroup(defaultGroupSettings.getMembersCanPostAsTheGroup())
                    .setMessageDisplayFont(defaultGroupSettings.getMessageDisplayFont())
                    .setIncludeInGlobalAddressList(defaultGroupSettings.getIncludeInGlobalAddressList());
            GoogleAppsSdkUtils.updateGroupSettings(groupssettingsClient, groupKey, groupSettings);
            recentlyManipulatedObjectsList.add(groupKey);


        } else {
          unarchiveGooGroupIfNecessary(googleGroup);
        }

        List<Member> groupMembers = CollectMembers(grouperGroup, googleGroup);
        GoogleAppsSdkUtils.addGroupMembersBulk(directoryClient, googleGroup.getEmail(), groupMembers);
    }

    private List<Member> CollectMembers(edu.internet2.middleware.grouper.Group grouperGroup, Group googleGroup) throws IOException {
        List<Member> gooMembers = new ArrayList<Member>();

        //Get Member, admins, updaters Subjects; remove admins/updaters with optout privs
        Set<edu.internet2.middleware.grouper.Member> members = grouperGroup.getMembers();

        Collection<edu.internet2.middleware.subject.Subject> adminsToUse = CollectionUtils.subtract(grouperGroup.getAdmins(), grouperGroup.getOptouts());
        for (Subject subj : adminsToUse) {
            members.add(MemberFinder.findBySubject(GrouperSession.staticGrouperSession(), subj, false));
        }
        Collection<edu.internet2.middleware.subject.Subject> updatersToUse = CollectionUtils.subtract(grouperGroup.getUpdaters(), grouperGroup.getOptouts());
        for (Subject subj : updatersToUse) {
            members.add(MemberFinder.findBySubject(GrouperSession.staticGrouperSession(), subj, false));
        }

        for (edu.internet2.middleware.grouper.Member member : members) {
            if (member.getSubjectType() == SubjectTypeEnum.PERSON) {
                Subject subject = fetchGrouperSubject(member.getSubjectSourceId(), member.getSubjectId());
                String userKey = fetchGooUserIdentifier(subject);
                User user = fetchGooUser(userKey);

                if (user == null) {
                    user = createGooUser(subject);
                }

                if (user != null) {
                    Member gooMember = new Member();
                    gooMember.setEmail(user.getPrimaryEmail());
                    gooMember.setRole(determineRole(member, grouperGroup));
                    gooMembers.add(gooMember)
;               }
            }
        }

        return gooMembers;
    }

    public String determineRole(edu.internet2.middleware.grouper.Member member, edu.internet2.middleware.grouper.Group group) {
        if ((properties.getWhoCanManage().equalsIgnoreCase("BOTH") && member.canUpdate(group))
                || (properties.getWhoCanManage().equalsIgnoreCase("ADMIN") && member.canAdmin(group))
                || (properties.getWhoCanManage().equalsIgnoreCase("UPDATE") && member.canUpdate(group) && !member.canAdmin(group))
           ) {
            return "MANAGER";
        } else if (member.isMember(group)) {
            return "MEMBER";
        }
        
        return null;
    }
    
    public void unarchiveGooGroupIfNecessary(Group group) throws IOException {
      String groupKey = group.getEmail();
      final Groups defaultGroupSettings = properties.getDefaultGroupSettings();

      recentlyManipulatedObjectsList.delayIfNeeded(groupKey);
      Groups groupssettings = GoogleAppsSdkUtils.retrieveGroupSettings(groupssettingsClient, groupKey);

      if (groupssettings.getArchiveOnly().equalsIgnoreCase("true")) {
          groupssettings.setArchiveOnly("false");
          groupssettings.setWhoCanPostMessage(defaultGroupSettings.getWhoCanPostMessage());

          GoogleAppsSdkUtils.updateGroupSettings(groupssettingsClient, groupKey, groupssettings);
          recentlyManipulatedObjectsList.add(groupKey);
      }
    }

    public void deleteGooGroup(edu.internet2.middleware.grouper.Group group) throws IOException {
        deleteGooGroupByName(group.getName());
    }

    public void deleteGooGroupByName(String groupName) throws IOException {
        final String groupKey = addressFormatter.qualifyGroupAddress(groupName);
        deleteGooGroupByEmail(groupKey);

        grouperGroups.remove(groupName);
        syncedObjects.remove(groupName);
    }

    public void emptyGooGroup(edu.internet2.middleware.grouper.Group group) throws IOException {
        LOG.debug("emptyGooGroup() - {}", group.getName());
        final String groupKey = addressFormatter.qualifyGroupAddress(group.getName());

        List<Member> members = getGooMembership(groupKey);

        GoogleAppsSdkUtils.removeGroupMembersBulk(directoryClient, groupKey, members);
    }

    public void deleteGooGroupByEmail(String groupKey) throws IOException {
        if (properties.getHandleDeletedGroup().equalsIgnoreCase("archive")) {
            recentlyManipulatedObjectsList.delayIfNeeded(groupKey);

            Groups gs = GoogleAppsSdkUtils.retrieveGroupSettings(groupssettingsClient, groupKey);
            gs.setArchiveOnly("true");
            gs.setWhoCanPostMessage("NONE_CAN_POST");
            GoogleAppsSdkUtils.updateGroupSettings(groupssettingsClient, groupKey, gs);

            recentlyManipulatedObjectsList.add(groupKey);

        } else if (properties.getHandleDeletedGroup().equalsIgnoreCase("delete")) {
            recentlyManipulatedObjectsList.delayIfNeeded(groupKey);

            GoogleAppsSdkUtils.removeGroup(directoryClient, groupKey);
            GoogleCacheManager.googleGroups().remove(groupKey);

            recentlyManipulatedObjectsList.delayIfNeeded(groupKey);
        }
        //else "ignore" (we do nothing)

    }


    /**
     * Finds the AttributeDefName specific to this GoogleApps ChangeLog Consumer instance.
     * @return The AttributeDefName for this GoogleApps ChangeLog Consumer
     */
    public AttributeDefName getGoogleSyncAttribute() {
        syncAttributeDefName = SYNC_TO_GOOGLE_NAME + consumerName;

        LOG.debug("Google Apps Consumer '{}' - looking for attribute: {}", consumerName, syncAttributeDefName);

        if (syncAttribute != null) {
            return syncAttribute;
        }

        AttributeDefName attrDefName = AttributeDefNameFinder.findByName(syncAttributeDefName, false);

        if (attrDefName == null) {
            Stem googleStem = StemFinder.findByName(GrouperSession.staticGrouperSession(), GOOGLE_CONFIG_STEM, false);

            if (googleStem == null) {
                LOG.info("Google Apps Consumer '{}' - {} stem not found, creating it now", consumerName, GOOGLE_CONFIG_STEM);
                final Stem etcAttributeStem = StemFinder.findByName(GrouperSession.staticGrouperSession(), ATTRIBUTE_CONFIG_STEM, false);
                googleStem = etcAttributeStem.addChildStem(GOOGLE_PROVISIONER, GOOGLE_PROVISIONER);
            }

            AttributeDef syncAttrDef = AttributeDefFinder.findByName(SYNC_TO_GOOGLE_NAME + "Def", false);
            if (syncAttrDef == null) {
                LOG.info("Google Apps Consumer '{}' - {} AttributeDef not found, creating it now", consumerName, SYNC_TO_GOOGLE + "Def");
                syncAttrDef = googleStem.addChildAttributeDef(SYNC_TO_GOOGLE + "Def", AttributeDefType.attr);
                syncAttrDef.setAssignToGroup(true);
                syncAttrDef.setAssignToStem(true);
                syncAttrDef.setMultiAssignable(true);
                syncAttrDef.setValueType(AttributeDefValueType.string);
                syncAttrDef.store();
            }

            LOG.info("Google Apps Consumer '{}' - {} attribute not found, creating it now", consumerName, syncAttributeDefName);
            attrDefName = googleStem.addChildAttributeDefName(syncAttrDef, SYNC_TO_GOOGLE + consumerName, SYNC_TO_GOOGLE + consumerName);
        }

        syncAttribute = attrDefName;

        return attrDefName;
    }

    public boolean shouldSyncGroup(edu.internet2.middleware.grouper.Group group) {
        boolean result;

        if (group == null) {
            return false;
        }

        final String groupName = group.getName();

        if (syncedObjects.containsKey(groupName)) {
            result = syncedObjects.get(groupName).equalsIgnoreCase("yes");

        } else {
            //result = group.getAttributeDelegate().retrieveAssignments(syncAttribute).size() > 0 || shouldSyncStem(group.getParentStem());
            String attributeResult = group.getAttributeValueDelegate().retrieveValueString(syncAttributeDefName);
            attributeResult = attributeResult == null ? "no" : attributeResult;
            result = attributeResult.equalsIgnoreCase("yes") || shouldSyncStem(group.getParentStem());
            syncedObjects.put(groupName, result ? "yes" : "no");
        }

        return result;
    }

    public boolean shouldSyncStem(Stem stem) {
        boolean result;

        final String stemName = stem.getName();

        if (syncedObjects.containsKey(stemName)) {
            result = syncedObjects.get(stemName).equalsIgnoreCase("yes");

        } else {
            //result = stem.getAttributeDelegate().retrieveAssignments(syncAttribute).size() > 0 || !stem.isRootStem() && shouldSyncStem(stem.getParentStem());
            String attributeResult = stem.getAttributeValueDelegate().retrieveValueString(syncAttributeDefName);
            attributeResult = attributeResult == null ? "no" : attributeResult;
            result = attributeResult.equalsIgnoreCase("yes") || !stem.isRootStem() && shouldSyncStem(stem.getParentStem());
            syncedObjects.put(stemName, result ? "yes" : "no");
        }

        return result;
    }

    public void cacheSyncedGroupsAndStems() {
        cacheSyncedGroupsAndStems(false);
    }
    public void cacheSyncedGroupsAndStems(boolean fullyPopulate) {
        /* Future: API 2.3.0 has support for getting a list of stems and groups using the Finder objects. */

        final ArrayList<String> ids = new ArrayList<String>();

        //First the Stems
        Set<AttributeAssign> attributeAssigns = GrouperDAOFactory.getFactory()
                .getAttributeAssign().findStemAttributeAssignments(null, null, GrouperUtil.toSet(syncAttribute.getId()), null, null, true, false);

        for (AttributeAssign attributeAssign : attributeAssigns) {
            if (attributeAssign.getOwnerStem().getAttributeValueDelegate().retrieveValueString(syncAttributeDefName).equalsIgnoreCase("true")) {
                ids.add(attributeAssign.getOwnerStemId());
            }
        }
        final Set<Stem> stems = StemFinder.findByUuids(GrouperSession.staticGrouperSession(), ids, new QueryOptions());
        for (Stem stem : stems) {
            syncedObjects.put(stem.getName(), "yes");

            if (fullyPopulate) {
                for (edu.internet2.middleware.grouper.Group group : stem.getChildGroups(Stem.Scope.SUB)) {
                    syncedObjects.put(group.getName(), "yes");
                }
            }
        }

        //Now for the Groups
        attributeAssigns = GrouperDAOFactory.getFactory()
                .getAttributeAssign().findGroupAttributeAssignments(null, null, GrouperUtil.toSet(syncAttribute.getId()), null, null, true, false);

        for (AttributeAssign attributeAssign : attributeAssigns) {
            if (attributeAssign.getOwnerGroup().getAttributeValueDelegate().retrieveValueString(syncAttributeDefName).equalsIgnoreCase("yes")) {
                final edu.internet2.middleware.grouper.Group group = GroupFinder.findByUuid(GrouperSession.staticGrouperSession(), attributeAssign.getOwnerGroupId(), false);
                syncedObjects.put(group.getName(), "yes");
            }
        }
    }


    public void removeGooMembership(String groupName, Subject subject) throws IOException {
        final String groupKey = addressFormatter.qualifyGroupAddress(groupName);
        final String userKey = fetchGooUserIdentifier(subject);

        if (userKey != null && !userKey.isEmpty()) {
            recentlyManipulatedObjectsList.delayIfNeeded(userKey);
            GoogleAppsSdkUtils.removeGroupMember(directoryClient, groupKey, userKey);
            recentlyManipulatedObjectsList.add(userKey);

            if (properties.shouldDeprovisionUsers()) {
                //FUTURE: check if the user has other memberships and if not, initiate the removal here.
            }

        } else {
            LOG.debug("{} does not have a valid Google email adress assigned", subject.getName());
        }
    }

    public void removeGooMembership(String groupKey, String subjectEmail) throws IOException {
      recentlyManipulatedObjectsList.delayIfNeeded(subjectEmail);
      GoogleAppsSdkUtils.removeGroupMember(directoryClient, groupKey, subjectEmail);
      recentlyManipulatedObjectsList.add(subjectEmail);

      if (properties.shouldDeprovisionUsers()) {
          //FUTURE: check if the user has other memberships and if not, initiate the removal here.
      }

      }
    
    public Group updateGooGroup(String groupKey, Group group) throws IOException {
        recentlyManipulatedObjectsList.delayIfNeeded(groupKey);
        final Group gooGroup = GoogleAppsSdkUtils.updateGroup(directoryClient, groupKey, group);
        recentlyManipulatedObjectsList.add(groupKey);

        return gooGroup;
    }

    public List<Member> getGooMembership(String groupKey) throws IOException {
        return GoogleAppsSdkUtils.retrieveGroupMembers(directoryClient, groupKey);
    }

    public AddressFormatter getAddressFormatter() {
        return addressFormatter;
    }

    public HashMap<String, String> getSyncedGroupsAndStems() {
        return syncedObjects;
    }

    public void createGooMember(edu.internet2.middleware.grouper.Group group, Subject subject, String role) throws IOException {
        User user = fetchGooUser(fetchGooUserIdentifier(subject));

        if (user == null) {
            user = createGooUser(subject);
        }

        Group gooGroup = fetchGooGroup(addressFormatter.qualifyGroupAddress(group.getName()));
        if (user != null && gooGroup != null) {
            createGooMember(gooGroup, user, role);
        }
    }


    public void updateGooMember(edu.internet2.middleware.grouper.Group group, Subject subject, String role) throws IOException {
        User user = fetchGooUser(fetchGooUserIdentifier(subject));

        if (user == null) {
            user = createGooUser(subject);
            if (user == null) {
                return;
            }
        }

        Group gooGroup = fetchGooGroup(addressFormatter.qualifyGroupAddress(group.getName()));
        if (gooGroup == null) {
            return;
        }

        recentlyManipulatedObjectsList.delayIfNeeded(gooGroup.getEmail());
        Member member = GoogleAppsSdkUtils.retrieveGroupMember(directoryClient, gooGroup.getEmail(), user.getPrimaryEmail());

        if (member == null) {
            createGooMember(gooGroup, user, role);
            return;
        }


        if (!member.getRole().equals(role)) {
            member.setRole(role);
            GoogleAppsSdkUtils.updateGroupMember(directoryClient, gooGroup.getEmail(), user.getPrimaryEmail(), member);
            recentlyManipulatedObjectsList.add(user.getPrimaryEmail());
        } else {
            LOG.debug("This member {} needs no update.", member.getEmail());
        }
    }

	/** Here we look for a user attribute that is attached to the subject that can be looked up.
	 *	If not, we use the subject Identifer expression.
	 *  @return the String that will be used for the Google User Identifier
	 */
	public String fetchGooUserIdentifier (Subject subject) {
		LOG.debug("In fetchGooUserIdentifier with a subject: " + subject);

		/** If the subject is null, just return **/
		if (subject == null) return null;
				
		if (attributeForGooUserLookup != null) {
			String gooSubjectIdentifier = subject.getAttributeValue( attributeForGooUserLookup );
			LOG.debug ("The subject identifier is "  + gooSubjectIdentifier);
			
			if (appendDomainToGooUserAttribute) {
				return String.format("%s@%s", gooSubjectIdentifier, domain);
			} else {
				return gooSubjectIdentifier;
			}
		} else {
			return addressFormatter.qualifySubjectAddress(subject.getId());
		}
	}

	public String getSyncAttributeDefName() {
	    return this.syncAttributeDefName;
    }

    public Directory getDirectoryClient() {
	    return this.directoryClient;
	}
}

