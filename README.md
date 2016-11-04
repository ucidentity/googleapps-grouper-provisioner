Google Apps Grouper Provisioner
==============================

#### Changes to the code base
The code base has been moved to the grouper project github site. <https://github.com/Internet2/grouper/tree/master/grouper-misc/googleapps-grouper-provisioner>. 

They made some nice changes and fixes. There are two that we made that are not represented there. One is a couple of settings that Google has added upon our request to allow management of the group in google groups but the membership is not changeable. The other is an issue we found with the subject identifier config. We couldn't find a way to access our google identifier using it. My expectation is that these can usefully be modified for the project. 

The only other change that we need that may also work for others is an overall membership limiting attribute with exclusions. Any group with more than "max" membership won't be provisioned. There needs to be an attribute that allows large group exclusions (allowLargeGroups). 

=============================


> Professional support/integration assistance for this module is available. For more information, visit <https://unicon.net/opensource/grouper>.

This project is a [Grouper](http://grouper.internet2.edu/) change log consumer and full sync agent that provisions (and deprovisions) Grouper groups and subjects to a Google Apps for Education/Business domain.

### Features
The Google Apps provisioner has the following features:

* Supports multiple provisioner instances/configurations.
* Fine-grain control over which groups are provisioned.
* Configure Google's "advanced" group settings.
* Optionally, set users with admin/update Grouper privileges as Google Group managers.
* Optionally, can provision Google user accounts.

### Instructions
Instructions on installation, configuration, and execution can be found on the [project's wiki](https://github.com/Unicon/googleapps-grouper-provisioner/wiki).

### Acknowledgements
Unicon's work on the Google Apps Grouper Provisioner project is funded through a project with Oregon State University. It is intended that the products (source code and deliverables) of this project will be donated to the Grouper project, and that rights will be assigned to Internet2.

These individuals have provided guidance through out the development process:

* Andy Morgan, Oregon State University 
* Erica Lomax, Oregon State University
* David Langenberg, University of Chicago
* Chris Hyzer, University of Pennsylvania
* Jeff Pasch, New York University
* Gary Chapman, New York University
* Madan Dorairaj, New York University
