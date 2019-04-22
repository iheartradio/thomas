### A/B Test Domain

Here is example A/B test presented in Json as in returned by service. 

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

### Field definitions: 

**name**: name of the test, it's more like a note/description, **it is NOT an identifier**. 

**feature**: feature name of the treatment. This is an identifier with which feature code can determine for each user which treatment they get.

**author**: another notes field

**start**: start of the test

**end**: end of the test, optional, if not given the test will last indefinitely

**groups**: group definitions. group sizes don't have to add up to 1, but they cannot go beyond 1. If the sum group size is less than 1, it means that there is a portion (1 - the sum group size) of users won't be the in tests at all, you could make this group your control group.


**requiredTags**: an array of string tags for eligibility control. Once set, only users having these tags (tags are passed in group assignment inquiry requests) are eligible for this test.
