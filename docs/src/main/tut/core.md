---
layout: page
title:  "Core API"
section: "Core"
position: 70
---


Here are the core concepts in Thomas.

* Feature - a string representing a feature in the product to be A/B tested. A/B tests in Thomas are organized by the feature they are testing against. For each feature there is at most one ongoing test at a specific time. A/B test treatment group assignments are by features, no by specific tests. That is, when Thomas receives an inquiry about the treatment group assignments for a user, it returns a list of pairs of feature and group name. Specific test ids are hidden from such inquiries. For a client to determine which treatment to give for a feature, all it needs is the assigned group name for that feature.   
          
* A/B test - represents a single experiment that gives different treatments to different groups of users for a certain feature during a period of time. It's often that people run multiple rounds of A/B tests to determine the optimal treatment for a feature. In Thomas, users can create one A/B test after another testing a specific feature. This series of A/B tests can be deemed as evolving versions of the experiment. The A/B test meta data becomes immutable after it starts. To revise a running A/B test experiment, user has to terminate it and start a new one. This immutability is required to guarantee the correctness of the history as well as enabling the distributed assignment computation.   


* Eligibility Control - mechanism to determine which users are eligible for which experiments. When a feature is not available to certain users, it is desirable to avoid providing any group assignment for these users. For one thing, it would be simply incorrect and may become source of false report in analytic data. This also allows targeting a subset of all users for a certain experiment.

    
* Overrides - to fix group assignment for certain users. User assignment is stochastic which could be tricky when QAing an A/B tests. Overrides allow users to fix group assignments for certain users so that QA can test expected behavior from the product for those users.  

* Group Meta - a meta data associated with a treatment group that is included in group assignment response. This metadata could potentially be used to determine the treatment end-user receives. Client could, instead of hard code different behavior based on group names, use some configuration to control the behavior. The group meta can provide such configurations. Thus different group can result in different treatment with different configuration in their group meta. 

* User Meta - more advanced mechanism for eligibility control. When client send a group assignment inquiry request, it can include a user metadata which then can be used to determine which users are eligible for which tests. The A/B tests can be set with a matchingUserMeta which is a list of pairs of field name and regex. Only users whose user meta field value matches the regex are eligible for the experiment.   


### A/B Test meta data

It represents the meta data regarding an A/B test experiment. Here is example A/B test presented in Json as in returned by service. 

```json

{
  "name": "A/B test against model v1.0",
  "feature": "Data Model",
  "author": "Kai", 
  "start": "2017-09-21T00:00:00.001-04:00",  
  "end": "2017-09-27T23:59:59.999-04:00",
  "groups": [ 
    {
      "name": "A",
      "size": 0.5
    },
    {
      "name": "B",
      "size": 0.5
    }
  ],
  "requiredTags": [],
  "alternativeIdName": "deviceId",
  "matchingUserMeta": {
    "age": "^2\d$"
  },
  "reshuffle": false,
  "segmentRanges": [
    {
      "start": 0,
      "end": 0.5
    }
  ]
}

```

**name**: name of the test, it's more like a note/description, **it is NOT an identifier**. 

**feature**: feature name of the treatment. This is an identifier with which feature code can determine for each user which treatment they get.

**author**: another notes field

**start**: start of the test

**end**: end of the test, optional, if not given the test will last indefinitely

**groups**: group definitions. group sizes don't have to add up to 1, but they cannot go beyond 1. If the sum group size is less than 1, it means that there is a portion (1 - the sum group size) of users won't be the in tests at all, you could make this group your control group.

**requiredTags**: an array of string tags for eligibility control. Once set, only users having these tags (tags are passed in group assignment inquiry requests) are eligible for this test.

**alternativeIdName**: by default Thomas uses the "userId" field passed in the group assignment inquiry request as the unique identification for segmenting users. In some A/B test cases, e.g. some features in the user registration process, a user Id might not be given yet. In such cases, you can choose to use any user meta field as the unique identification for users. 

**matchingUserMeta**: this is more advanced eligibility control. You can set a field and regex pair to match user meta. Only users whose metadata field value matches the regex are eligible for the experiment. 

**reshuffle**: by default Thomas will try to keep the user assignment consistent between different versions of experiments of a feature. Setting this field to true will redistribute users again among all groups.  

**segmentRanges**: this field is used for creating mutually exclusive tests. When Thomas segments users into different groups, it hashes the user Id to a number between 0 and 1. If an A/B test is set with a set of segment ranges, then only hashes within that range will be eligible for that test. Thus if two tests have non-overlapping segment ranges, they will be mutually exclusive, i.e. users who eligible for one will not be eligible for the other. 
