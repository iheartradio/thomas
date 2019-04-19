
# Thomas - a new A/B test library

[![Build Status](https://travis-ci.org/iheartradio/thomas.svg?branch=master)](https://travis-ci.org/iheartradio/thomas)

Thomas is a modular library for setting up an A/B test service for A/B test management and user group assignment. It also provides tools for results analysis. 
To use it as a complete A/B test solution, a team needs to: 

1. be able to modify and deploy either play or http4s applications
2. have their own analytic platform and access to data  
3. be able to read and understand Scala code in pure functional paradigm.  
4. run and maintain a tiny MongoDB cluster or implement a data access layer.  

Still here? Ok, this is the set of features that differentiate Thomas from other existing A/B frameworks/libraries.  
  
## Features 

* Real-time hash-based assignment 
* Eligibility control decoupled from user domain
* Experiment evolution with consistent user assignments
* Bayesian analysis
* Mutually exclusive experiments
* Integration with Spark (and Pyspark) support
* High Performance
* Modular


### Real-time hash-based assignment

Thomas' A/B group assignment for users is always done real-time by a mathematical function that deterministically and uniformly distribute users into different groups. This function takes in a time, a user id and her relevant meta info, it then returns the assignments of all A/B test experiments running at that time. Thomas does not batch pre-assign users nor does it store the assignments anywhere. Every time you ask Thomas for group assignments of a user, it will run that mathematical function to answer you. 

This real-time assignment algorithm may seem mundane, but it has important implications. Particularly, it has to be strictly deterministic, as a mathematical function should be. With the same input, user info and time stamp, it guarantees to return the same assignments whenever it's called. To ensure that Thomas enforces that all A/B test experiments are read-only. To evolve a experiment, you have to terminate the existing one and start a new version. 

Other implications includes:

1. Experiments' metadata is the only data that need to be persistent by Thomas. This makes the data storage requirements in Thomas trivial. 
2. Since there is no user lookup, Thomas is more scalable in regards to the number of end users - although that might still affect service call volume. 
3. Assignments computation can be distributed. In fact, Thomas provides a thomas-client that can compute assignments in parallel. More details below.

### A/B test experiment evolution with consistent user assignments

It's common that an A/B test consists of several rounds of experiments, with minor modifications of treatment and/or group size. Sometimes it  is preferable not to reassign existing users within each group which will cause abrupt discontinuity in their user experiences. Thomas supports such experiment evolution with consistent user assignments. 

When a new experiment of an A/B test is rolled out with different group sizes, Thomas can be set to either completely redistribute all users uniformly or keep the user assignment as consistent as possible - when a group is enlarged, all of its existing users will remain in it; when a group is shrunk, a minimally required portion of its existing users will be reassigned to other groups.Thomas does this without deviating from the real-time assignment algorithm.

This also enables Thomas to double as a valid gradual feature roll out system. A small treatment group can increase its size incrementally to roll out a feature. There is no disruptions of user experience in this process thanks to the consistent user assignment. 

### Bayesian analysis


### Eligibility control decoupled from user domain

Thomas is user agnostic. It does not store any information regarding any user, assigns users in real time, and does not require any integration with in house user information storage. The Thomas's database stores only the experiments' meta-data.

Without this access to user information, Thomas uses a different mechanism to control users' eligibility for certain A/B test experiments: when a client calls Thomas assignment service, it can pass in a small set of relevant meta data of the user. Experiments can be set with some eligibility criteria that filter users based on the meta passed in. 

### Mutually exclusive experiments

Thomas provides an easy way to support mutually exclusive A/B test experiments, i.e., a user could join either experiment but not both. An experiment can be set with arbitrary numeric range within the range 0 to 1. A/B test experiments with non-overlapping ranges are mutually exclusive with each other. 

### Distributed assignment / Spark support

Thomas provides a thomas-client that can pull down experiments metadata and calculate user assignments in parallel on local CPU. It also provides a native Java API that can be used in Spark and Pyspark. This would allow users to efficiently batch process data according to A/B test group assignment without any extra workload to the assignment service.

### High Performance 

A/B test information retrieval is an overhead for all features running A/B tests. High performance is an uncompromisable top priority for Thomas since the very beginning. Thanks to the real-time assignment design, Thomas is able to easily keep all necessary data in memory when serving user assignment requests. Moreover, it's written in "sleek performant low-overhead Scala code with high-order functions that would run on anything. Period. End of sentence." 
When running on a MacBook Pro, with about two dozens of ongoing experiments, server side response time 95th percentile is 4ms, 99th percentile is 8ms. When running on production server at iHeartRadio, the 99th percentile is 1ms.   

### Modular and lightweight

Thomas is developed in small modules so that users can pick and choose what they need. 
 - thomas-core: core logic and algorithms
 - thomas-http4s: library for creating a Http service using Http4s, also runs out of box
 - thomas-play-lib: library for creating a Http service using Play framework
 - thomas-mongo: data access layer using MongoDB
 - thomas-client: distributed client that provides assignment by running the algorithm locally
 - thomas-analysis: Bayesian analysis utilities
 
 
## What's not included / still lacking in Thomas

* No built-in analytics
* No Admin UI
* No built-in authentication/authorization system for management API
* Documentation

 
### Admin UI not included

The web UI and Android App for managing experiments in Thomas we use at iHeartRadio are proprietary mainly due to their necessary integration with in house authentication system. User can either directly use the service API or the swagger based UI that can be copied from the example.  


## Thomas at iHeartRadio 

Thomas has been serving A/B tests at iHeartRadio for over a year. Usually it runs a couple dozens of ongoing A/B test experiments with our 120+ million registered users. There were a couple of glitches during the early phase but has been stabilized and issue free for more than half a year. If you are considering running an in-house A/B test platform, please consider give Thomas a try.  
