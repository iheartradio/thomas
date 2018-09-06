

## Why another open-source A/B test library
 A/B test library

### Features 

* Focus on the user assignment side
* Decoupled from user domain
* Real-time hash-based assignment
* Modular and lightweight
* Experiment evolution with consistent user assignments
* Eligibility control
* Mutually exclusive experiments
* Configurable user identifier
* Integration with Spark (and Pyspark) support
* High Performance


## Focus on the user assignment side

There are 6 logic steps to run an A/B test: 
1. define an experiment with groups
2. determine which users are eligible to be in the experiment
3. randomly assign users to different groups
4. give users the treatment based on their assignment
5. report subsequent actions/events from the users together with their assignment
6. analyze the results and try draw conclusions regarding the effects of the treatments

At the moment, Thomas focuses on step 1 and 3. It supports step 2 through a tag/meta matching mechanism. It supports step 4 by providing an HTTP service to retrieve user assignments. There is a plan for Thomas to support step 6 in some capacity in the future. For step 5, i.e. reporting/analytics, users of Thomas will need to use an analytics service such as Adobe Analytics, tableau or Google Analytics. 

## Decoupled from user domain

Thomas took a user agnostic approach. It does not store any information regarding any user, the assignment happens in real time, and it does not require any integration with any user information storage. The database of Thomas stores only the experiments' meta-data.

## Real-time assignment

The user assignment is a stochastic and deterministic mathematic function. The input to this function is the user's identifier and the experiment's immutable metadata.  For the same input, the group assignment will always be the same regardless of the time and the machine the code runs on.  This deterministic assignment function has several benefits: 
1. Assignments can happen highly distributed. E.g. Thomas provides a library that can calculate assignment in parallel in Spark.
2. Experiments' metadata is the only data that need to be persistent by Thomas. This makes the data storage requirements in Thomas trivial. 
3. Since there is no user lookup, Thomas is more scalable in regards to the number of end users. 
4. Change to the experiments reflects almost instantaneously to the user assignments. There is no re-assign needed. 

## Eligibility control

Without direct access to user information, Thomas uses a different mechanism to control users' eligibility for experiments: when a client calls Thomas assignment service it can pass in a small set of relevant information of the user. Experiments can have some eligibility control criteria that filter users based on the information passed in. 
