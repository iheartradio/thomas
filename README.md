
# Thomas - a new A/B test library

[![Build Status](https://travis-ci.org/iheartradio/thomas.svg?branch=master)](https://travis-ci.org/iheartradio/thomas)

## Features 

* Focus on user assignment
* Decoupled from user domain
* Real-time hash-based assignment
* Experiment evolution with consistent user assignments
* Eligibility control
* Mutually exclusive experiments
* Integration with Spark (and Pyspark) support
* High Performance
* Modular and lightweight


### Focus on user assignment

There are 6 logic steps to run an A/B test: 
1. Define an experiment with user groups
2. Determine which users are eligible to be in the experiment
3. Randomly assign users to groups
4. Give users a treatment based on their assigned group
5. Report subsequent actions/events from the users with their assignments
6. Analyze the results and try draw conclusions on the effects of the treatments

At the moment, Thomas focuses on step 1 and 3. 

It supports step 2 through a tag/meta matching mechanism. 

It supports step 4 by providing an HTTP service to retrieve user assignments. 

For step 5, i.e., reporting/analytics, users of Thomas will need to use an analytics service such as Adobe Analytics, tableau or Google Analytics. 

Thomas may support step 6 in some capacity in the future. 

### Experiment evolution with consistent user assignments
When an experiments userbase or treatments groups need to be modified, it is often preferable not to reassign existing users. Thomas supports this kind of experiment evolution with consistent user assignments. 

By default, when an experiment is evolved with a different group composition, Thomas will change the group composition as little as possible. 

Users in groups that are enlarged will not be reassigned. Users in groups that are shrunk will only be reassigned as necessary to meet the size requirement.

This enables Thomas to support feature roll out. A small treatment group can be increase its size to roll out a feature gradually. 

### Real-time assignments

The user assignment is a stochastic and deterministic mathematic function. The input to this function is the user's identifier and the experiment's immutable metadata.  For the same input, the group assignment will always be the same regardless of the time and the machine the code runs on.  This deterministic assignment function has several benefits: 
1. Assignments can be distributed. E.g., Thomas provides a library that can calculate assignment in parallel in Spark.
2. Experiments' metadata is the only data that need to be persistent by Thomas. This makes the data storage requirements in Thomas trivial. 
3. Since there is no user lookup, Thomas is more scalable in regards to the number of end users. 
4. Changes to the experiments reflect almost instantaneously to the user assignments. There is no re-assign needed. 

### Decoupled from user domain

Thomas is user agnostic. It does not store any information regarding any user, assigns users in real time, and does not require any integration with any user information storage. The Thomas's database stores only the experiments' meta-data.

### Eligibility control

Without direct access to user information, Thomas uses a different mechanism to control users' eligibility for experiments: when a client calls Thomas assignment service, it can pass in a small set of relevant information of the user. Experiments can have some eligibility control criteria that filter users based on the information passed in. 

### Mutually exclusive experiments

Thomas provides an easy way to support mutually exclusive experiments, i.e., a user could join either experiment but not both. An experiment can have a numeric range. Two experiments with non-overlapping ranges are mutually exclusive. 

### Distributed assignment / Spark support

Thomas provides a jar that can pull down experiments metadata and calculate user assignments in parallel on local CPU. It provides a native Java API that can be used in Spark and Pyspark. This would allow users to efficiently batch process data according to A/B test group assignment without any extra workload to the assignment service.

### High Performance 

A/B test information retrieval is an overhead for all features running A/B tests. High performance has been an uncompromisable top priority for Thomas since the very beginning. Thanks to the design, Thomas is able to easily keep all necessary data in memory when serving user assignment requests. Moreover, it's written in "sleek performant low-overhead Scala code with high-order functions that would run on anything. Period. End of sentence." 
When running on a MacBook Pro, with about two dozens of ongoing experiments, server response time 95th percentile is 4ms, 99th percentile is 8ms. 

### Modular and lightweight

Thomas is developed into small modules. 
 - thomas-core: Logic and algorithms
 - thomas-play-lib: Http service
 - thomas-client: Distributed client that can calculate assignments.
 With this approach, it's easy to create/adopt a different service layer, e.g. gRPC, or a different client implementation. 

## Admin UI not included

The web UI and for Android app for managing experiments in Thomas we use at iHeartRadio are proprietary. So as of now, users can either directly use the web service or a Swagger-UI that comes with the play app. 

## Next steps
We have some ideas of how to push Thomas forward. For now, to avoid overcommitment, we'd keep it the following vague list. 

1. Utils for experiment results analysis, particularly tools to support Bayesian A/B tests. 
2. Open source Admin UI
3. Support online learning such as multi-armed bandit algorithm, reinforcement learning, etc. 


