package com.iheart.thomas.abtest

import model.UserMetaCriterion._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class UserMetaCriterionSuite extends AnyFreeSpec with Matchers {

  "equalOrAfter for version compare" - {
    import VersionRange.equalOrAfter
    "return true for major number" in {
      equalOrAfter("1.2", "2.1") shouldBe true
    }

    "return true for identical versions" in {
      equalOrAfter("1.2", "1.2") shouldBe true
    }

    "return false for minor versions" in {
      equalOrAfter("1.13", "1.2") shouldBe false
    }

    "return true when with extra version parts" in {
      equalOrAfter("1.13", "1.13.1") shouldBe true
    }

    "ignore patch number" in {
      equalOrAfter("1.3.2", "1.2.3") shouldBe false
      equalOrAfter("1.2", "1.3.4") shouldBe true
    }

    "String part is ignored" in {
      equalOrAfter("1.3-RC3", "1.3-RC2") shouldBe true
    }

  }

  "eligible for" - {
    "RegexMatch" - {
      "returns true if there is a regex match " in {
        RegexMatch("f", "\\d\\dst")
          .eligible(Map("f" -> "my23student")) shouldBe true
      }

      "returns false if there is no regex match " in {
        RegexMatch("f", "\\d\\d\\d")
          .eligible(Map("f" -> "my23student")) shouldBe false

      }

      "returns false if empty" in {
        RegexMatch("f", "\\d\\d\\d")
          .eligible(Map()) shouldBe false
      }
    }

    "ExactMatch" - {
      "returns true if there exact match " in {
        ExactMatch("f", "blah").eligible(Map("f" -> "blah")) shouldBe true
      }

      "returns false if not exact match " in {
        ExactMatch("f", "blah").eligible(Map("f" -> "blah1")) shouldBe false
      }

      "returns false if empty " in {
        ExactMatch("f", "blah").eligible(Map()) shouldBe false
      }
    }

    "InMatch" - {
      "returns true if there is one value match" in {
        in("f", "blah1", "blah2").eligible(Map("f" -> "blah2")) shouldBe true
      }

      "returns false if no hit" in {
        in("f", "blah1", "blah2").eligible(Map("f" -> "blah3")) shouldBe false
      }

      "returns false if empty " in {
        in("f", "blah").eligible(Map()) shouldBe false
      }
    }

    "VersionRange" - {
      "return false if empty" in {
        VersionRange("f", "1.0", None).eligible(Map()) shouldBe false
      }

      "return true if within closed range" in {
        VersionRange("f", "1.2", Some("1.4.0"))
          .eligible(Map("f" -> "1.3")) shouldBe true
      }

      "return true if within open range" in {
        VersionRange("f", "1.2")
          .eligible(Map("f" -> "1.3")) shouldBe true
      }

      "return false if outside open range" in {
        VersionRange("f", "1.2")
          .eligible(Map("f" -> "1.1")) shouldBe false
      }
    }

    "And" - {
      "returns true iff both are true" in {
        and(ExactMatch("f", "ABC"), RegexMatch("f", "BC"))
          .eligible(Map("f" -> "ABC")) shouldBe true

        and(ExactMatch("f", "AB"), RegexMatch("f", "BC"))
          .eligible(Map("f" -> "ABC")) shouldBe false
      }
    }

    "Or" - {
      "returns true if either is true" in {
        or(ExactMatch("f", "ABC"), RegexMatch("f", "BCD"))
          .eligible(Map("f" -> "ABC")) shouldBe true

        and(ExactMatch("f", "AB"), RegexMatch("f", "BCD"))
          .eligible(Map("f" -> "ABC")) shouldBe false
      }
    }

  }

}
