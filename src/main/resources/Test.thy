theory Test
  imports Main
begin

lemma test: "p ==> q
==> :p"
  by simp

lemma \<open>
\<exists>x. drunk x \<longrightarrow> (\<forall>x. drunk x)
\<close>
    sorry

end