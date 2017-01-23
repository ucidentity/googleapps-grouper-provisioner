package edu.internet2.middleware.changelogconsumer.googleapps.utils;

import edu.internet2.middleware.grouper.Group;
import edu.internet2.middleware.grouper.GrouperSession;
import edu.internet2.middleware.grouper.attr.AttributeDefName;
import edu.internet2.middleware.grouper.attr.finder.AttributeDefNameFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper class used to limit the provisioner users from accidentally adding large numbers of members to Google Groups.
 */
public class AllowLargeGroupsUtils {
    private static final Logger LOG = LoggerFactory.getLogger(AllowLargeGroupsUtils.class);

    private final GoogleAppsSyncProperties properties;

    private final AttributeDefName allowLargeGroupsAttributeDefName;

    public AllowLargeGroupsUtils(GoogleAppsSyncProperties properties) {
        this.properties = properties;

        GrouperSession grouperSession = GrouperSession.startRootSession();
        this.allowLargeGroupsAttributeDefName = AttributeDefNameFinder.findByName(properties.getAllowLargeGroupsAttributeDefName(), false);
        GrouperSession.stopQuietly(grouperSession);

        if (allowLargeGroupsAttributeDefName == null) {
            LOG.warn("{} attribute def name does not exists. Override functionality will not work.", properties.getAllowLargeGroupsAttributeDefName());
        }
    }

    public boolean blockNewMember(final Group group) {
        if (group.getMemberships().size() < properties.getLargeGroupSize()) {
            return false;

        } else if (allowLargeGroupsAttributeDefName != null) {
            String value = group.getAttributeValueDelegate().retrieveValueString(allowLargeGroupsAttributeDefName.getName());

            if (value != null && value.equalsIgnoreCase("yes")) {
                return false;
            }
        }

        LOG.warn("Group contains more than {} members and has no allowLargerGroups override attribute; skipping.", properties.getLargeGroupSize());
        return true;

    }
}