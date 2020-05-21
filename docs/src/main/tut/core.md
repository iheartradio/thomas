---
layout: page
title:  "Core concepts"
section: "Core"
position: 70
---



### Core concepts


* Feature - a string representing a feature in the product to be A/B tested. 
A/B tests in Thomas are organized by the feature they are testing against. 
For each feature there is at most one ongoing test at a specific time.
A/B test treatment group assignments are by features, no by specific tests. 
That is, when Thomas receives an inquiry about the treatment group assignments for a user,
 it returns a list of pairs of feature and group name.
 Specific test ids are hidden from such inquiries. 
 For a client to determine which treatment to give for a feature,
  all it needs is the assigned group name for that feature.   
          
* A/B test - represents a single experiment that gives different treatments 
to different groups of users for a certain feature during a period. 
It's often that people run multiple rounds of A/B tests to determine the optimal 
treatment for a feature. In Thomas, users can create one A/B test after another 
testing a specific feature. This series of A/B tests can be deemed as evolving 
versions of the experiment. The A/B test's group setup becomes immutable after it starts.
 To revise a running A/B test experiment, user has to terminate it and start a new one. 
 This immutability is required to guarantee the correctness of the history as well 
 as enabling the distributed assignment computation. 
 For more details about the metadata in an A/B test, check [the API documentation of AbtestSpec](https://iheartradio.github.io/thomas/api/com/iheart/thomas/model/AbtestSpec.html)


* Eligibility Control - 
Mechanism to determine which users are eligible for which experiments. 
When a feature is not available to certain users, it is desirable to avoid
 providing any group assignment for these users. 
For one thing, it may become a source of false report in analytic data. 
This feature also allows targeting a subset of all users for a certain experiment.

* User Meta - When client send a group
 assignment inquiry request, it can include a Json object as the meta data for the user. 
  This then can be used for eligibility control. A/B tests can be set with a 
 filtering criteria to determine which users are eligible based on 
  the user meta passed in. These criteria is written in query language in JSON format.     
    

* Group Meta - a meta data associated with a treatment group that is included in group 
assignment response. This metadata could potentially be used to determine the treatment
 end-user receives. Client could, instead of hard code different behavior based on group
  names, use some configuration to control the behavior. 
  The group meta can provide such configurations. Thus different group assignment can result
   in different treatment thanks to the different configurations in their group meta. 

* Overrides - to fix group assignment for certain users. User assignment is stochastic 
which could be tricky when QAing an A/B tests. Overrides allow users to fix group assignments
 for certain users so that QA can test expected behavior from the product for those users.  