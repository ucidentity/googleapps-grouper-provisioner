package edu.internet2.middleware.changelogconsumer.googleapps.utils;

import edu.internet2.middleware.grouper.Group;
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

        this.allowLargeGroupsAttributeDefName = AttributeDefNameFinder.findByName(properties.getAllowLargeGroupsAttributeDefName(), false);

        if (allowLargeGroupsAttributeDefName == null) {
            LOG.warn("{} attribute def name does not exists. Override functionality will not work.", properties.getAllowLargeGroupsAttributeDefName());
        }
    }

    public boolean blockNewMember(final Group group) {

    /*    return (Boolean) GrouperSession.callbackGrouperSession(
                // Grab a static session
                GrouperSession.staticGrouperSession().internal_getRootSession(), new GrouperSessionHandler() {
                    public Object callback(final GrouperSession grouperSession) throws GrouperSessionException {
    */
                        if (group.getEffectiveMembers().size() < properties.getLargeGroupSize()
                                || (allowLargeGroupsAttributeDefName != null
                                && group.getAttributeDelegate().retrieveAssignments(allowLargeGroupsAttributeDefName).size() > 0)) {
                            return Boolean.FALSE;
                        }

                        LOG.warn("Group contains more than {} members and has no allowLargerGroups override attribute; skipping.", properties.getLargeGroupSize());
                        return Boolean.TRUE;
    /*                }
                });
    }*/
    }

}
