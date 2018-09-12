

In the past year, we at iHeartRadio developed an A/B test platform

## Features 

* Focus on the user assignment side
* Decoupled from user domain
* Real-time hash-based assignment
* Experiment evolution with consistent user assignments
* Eligibility control
* Mutually exclusive experiments
* Integration with Spark (and Pyspark) support
* High Performance
* Modular and lightweight


### Focus on the user assignment side

There are 6 logic steps to run an A/B test: 
1. define an experiment with groups
2. determine which users are eligible to be in the experiment
3. randomly assign users to different groups
4. give users the treatment based on their assignment
5. report subsequent actions/events from the users together with their assignment
6. analyze the results and try draw conclusions regarding the effects of the treatments

At the moment, Thomas focuses on step 1 and 3. It supports step 2 through a tag/meta matching mechanism. It supports step 4 by providing an HTTP service to retrieve user assignments. There is a plan for Thomas to support step 6 in some capacity in the future. For step 5, i.e., reporting/analytics, users of Thomas will need to use an analytics service such as Adobe Analytics, tableau or Google Analytics. 

### Experiment evolution with consistent user assignments
Sometimes we want to expand an experiment with more users getting the treatment or add new treatments, but don't want to reshuffle the user assignments which affects the users already getting the treatment.  I.e., we want to keep as many users' experiences unchanged as possible.  For example, suppose we start an experiment with 10% of users going to the treatment group, and for whatever reason, we want to expand that to 20% of the users. We would want the original 10% users to remain in the treatment group to avoid their experience being disrupted. Thomas supports this kind of experiment evolution with consistent user assignments. 

By default, when you evolve an experiment with different group composition, Thomas will keep the assignment as consistent as prior group composition as possible.  For groups that are enlarged, i.e., receive a higher percentage of the total population, all users that were assigned to this group previously will remain in it, meaning no treatment change to them. For groups that are shrunk, i.e., receives a lower percentage of the total population, obviously, some users will have to leave this group, but no new users will be added to this group. All users in the newly adjusted group were previously assigned to this group. I.e., no one in the new smaller group experiences a treatment change. 

This enables Thomas to also support feature roll out. You can start with a small treatment group and systematically increase its size to roll out a feature gradually. 

### Real-time assignments

The user assignment is a stochastic and deterministic mathematic function. The input to this function is the user's identifier and the experiment's immutable metadata.  For the same input, the group assignment will always be the same regardless of the time and the machine the code runs on.  This deterministic assignment function has several benefits: 
1. Assignments can be distributed. E.g., Thomas provides a library that can calculate assignment in parallel in Spark.
2. Experiments' metadata is the only data that need to be persistent by Thomas. This makes the data storage requirements in Thomas trivial. 
3. Since there is no user lookup, Thomas is more scalable in regards to the number of end users. 
4. Changes to the experiments reflect almost instantaneously to the user assignments. There is no re-assign needed. 

### Decoupled from user domain

Thomas took a user agnostic approach. It does not store any information regarding any user, the assignment happens in real time, and it does not require any integration with any user information storage. The database of Thomas stores only the experiments' meta-data.

### Eligibility control

Without direct access to user information, Thomas uses a different mechanism to control users' eligibility for experiments: when a client calls Thomas assignment service it can pass in a small set of relevant information of the user. Experiments can have some eligibility control criteria that filter users based on the information passed in. 

### Mutually exclusive experiments

Thomas provides an easy way to support mutually exclusive experiments, i.e., users could join one experiment or the other but not both. You can set a numeric range for an experiment. As long as the ranges you set for two experiment don't overlap, they would be mutually exclusive. 

### Distributed assignment / Spark support

Thomas provides a jar that can pull down experiments metadata and calculate user assignments in parallel on local CPU. It provides a native Java API that can be used in Spark and Pyspark. This would allow users to efficiently batch process data according to A/B test group assignment without any extra workload to the assignment service.

### High Performance 

A/B test information retrieval is an overhead for all features running A/B tests. High performance has been an uncompromisable top priority for Thomas since the very beginning. Thanks to the design, Thomas is able to easily keep all necessary data in memory when serving user assignment requests. Moreover, it's written in "sleek performant low-overhead Scala code with high-order functions that would run on anything. Period. End of sentence." 
When running on a MacBook Pro, with about two dozens of ongoing experiments, server response time 95th percentile is 4ms, 99th percentile is 8ms. 

### Modular and lightweight

Thomas is developed into small modules. The logic and algorithms are in thomas-core. The Http service is provided through a different module - thomas-play-lib. Thomas-client provides a distributed client that can calculate assignments. With this approach, it's easy to create/adopt a different service layer, e.g. gRPC, or a different client implementation. 

## Admin UI not included

The web UI and for Android app for managing experiments in Thomas we use at iHeartRadio are proprietary. So as of now, users can either directly use the web service or a Swagger-UI that comes with the play app. 

## Next steps
We have some ideas of how to push Thomas forward. For now, to avoid overcommitment, we'd keep it the following vague list. 

1. Utils for experiment results analysis, particularly tools to support Bayesian A/B tests. 
2. Open source Admin UI
3. Support online learning such as multi-armed bandit algorithm, reinforcement learning, etc. 




