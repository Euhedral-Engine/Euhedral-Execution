"""Serializable linear evaluators and allocation-free task-specific Java functions."""
from __future__ import annotations

import numpy as np
from scipy.special import expit, softmax

from .feature_schema import FeatureSchema, evaluate, expression
from .training_spec import Basis


def evaluator(spec, fit, threshold, controls=None):
  if spec.export.kind == "none":
    return None
  model = fit.model
  if model.coefficients is None and model.constant is None:
    raise ValueError("model has no supported evaluator")
  if spec.export.kind == "timing_table" and (
      controls is None or not len(controls)):
    raise ValueError("timing export requires measured control tuples")
  if spec.export.kind == "timing_table":
    for i, variable in enumerate(spec.controls):
      values = controls[:, i]
      bounds = spec.export.bounds.get(variable.name)
      if bounds and (np.any(values < bounds[0]) or np.any(values > bounds[1])):
        raise ValueError("export bounds would change a measured control tuple")
      if spec.export.rounding != "none" and np.any(values != np.floor(values)):
        raise ValueError("rounding would create an unmeasured control tuple")
  result = {"schemaVersion": 1, "taskId": spec.id, "taskHash": spec.schema_hash,
            "kind": spec.kind, "export": spec.export.model_dump(mode="json"),
            "inputs": [v.model_dump(mode="json") for v in
                       spec.features + spec.controls],
            "targets": [v.model_dump(mode="json") for v in spec.targets],
            "featureCount": len(spec.features),
            "schema": fit.schema.serialize(),
            "coefficients": None if model.coefficients is None else model.coefficients.tolist(),
            "intercept": None if model.intercept is None else model.intercept.tolist(),
            "constantClass": model.constant,
            "classes": None if model.classes is None else model.classes.tolist(),
            "labels": spec.labels, "threshold": threshold,
            "decisionOutput": spec.objective.decisionOutput,
            "controlTable": None if controls is None else controls.tolist()}
  # Export validation happens before any files are written.
  render_java(result)
  return result


def _inverse(value, transform):
  op = expression(transform).op
  if op == "identity":
    return value
  if op == "log":
    return np.exp(value)
  if op == "log1p":
    return np.expm1(value)
  raise ValueError("exported regression target supports identity/log/log1p")


def predict(artifact, raw):
  raw = np.asarray(raw, dtype=float)
  inputs = artifact["inputs"]
  if raw.ndim != 2 or raw.shape[1] != len(inputs):
    raise ValueError("raw evaluator input width mismatch")
  values = {v["source"]: raw[:, i] for i, v in enumerate(inputs)}
  from .training_spec import Expression
  x = np.column_stack([evaluate(
    Expression.model_validate(v["transform"]) if isinstance(v["transform"],
                                                            dict)
    else v["transform"], values, raw[:, i]) for i, v in enumerate(inputs)])
  saved = artifact["schema"]
  schema = FeatureSchema(saved["inputNames"],
                         Basis.model_validate(saved["basis"]))
  if schema.basis.scalingOrder == "after_expansion":
    x = schema.expand(x)
  if saved["mean"] is not None:
    x = (x - np.array(saved["mean"])) / np.array(saved["scale"])
  if schema.basis.scalingOrder == "before_expansion":
    x = schema.expand(x)
  if artifact["constantClass"] is not None:
    result = np.zeros((len(x), len(artifact["labels"])))
    result[:, artifact["constantClass"]] = 1
    return result
  result = x @ np.array(artifact["coefficients"]).T + np.array(
      artifact["intercept"])
  if artifact["kind"] == "classification":
    probabilities = np.column_stack(
        (1 - expit(result[:, 0]), expit(result[:, 0]))) if result.shape[
                                                             1] == 1 else softmax(
      result, axis=1)
    output = np.zeros((len(x), len(artifact["labels"])))
    output[:, artifact["classes"]] = probabilities
    return output
  for i, target in enumerate(artifact["targets"]):
    transform = target["transform"]
    if isinstance(transform, dict):
      transform = Expression.model_validate(transform)
    result[:, i] = _inverse(result[:, i], transform)
    bounds = artifact["export"]["bounds"].get(target["name"]) if \
    artifact["export"]["kind"] == "linear" else None
    if bounds:
      result[:, i] = np.clip(result[:, i], *bounds)
    rounding = artifact["export"]["rounding"] if artifact["export"][
                                                   "kind"] == "linear" else "none"
    if rounding == "nearest":
      result[:, i] = np.floor(result[:, i] + .5)
    elif rounding == "floor":
      result[:, i] = np.floor(result[:, i])
  return result


def timing_index(artifact, raw_features):
  count = artifact["featureCount"]
  target = [x["name"] for x in artifact["targets"]].index(
      artifact["decisionOutput"])
  return np.array([int(np.argmax(predict(artifact, np.array(
      [list(row) + pair for pair in artifact["controlTable"]]))[:, target]))
                   for row in np.asarray(raw_features).reshape(-1, count)])


def _java_expression(expr, values, default):
  from .training_spec import Expression
  expr = Expression.model_validate(expr) if isinstance(expr,
                                                       dict) else expression(
    expr)
  args = [values[name] for name in expr.args] if expr.args else [default]
  a = args[0]
  if expr.op == "identity":
    return a
  if expr.op == "scale":
    return f"({a} * {expr.factor!r})"
  if expr.op == "ratio":
    return f"({a} / {args[1]})"
  if expr.op == "product":
    return f"({a} * {args[1]})"
  if expr.op == "square":
    return f"({a} * {a})"
  if expr.op in {"log", "log1p"}:
    return f"Math.{expr.op}({a})"
  return f"Math.max({expr.bounds[0]!r}, Math.min({expr.bounds[1]!r}, {a}))"


def render_java(artifact):
  inputs = artifact["inputs"]
  args = ", ".join(f"double x{i}" for i in range(len(inputs)))
  calls = ", ".join(f"x{i}" for i in range(len(inputs)))
  sources = {v["source"]: f"x{i}" for i, v in enumerate(inputs)}
  saved = artifact["schema"]
  basis = Basis.model_validate(saved["basis"])
  lines = [
    "// Generated offline. Inputs must satisfy the serialized task domain.",
    "public final class TaskEvaluator {", "    private TaskEvaluator() {}"]
  body = []
  values = {}
  for i, variable in enumerate(inputs):
    try:
      expr = _java_expression(variable["transform"], sources, f"x{i}")
    except KeyError as error:
      raise ValueError(
        f"export needs a raw input for expression source {error}") from None
    body.append(f"        double v{i} = {expr};")
    values[variable["name"]] = f"v{i}"
  if basis.scalingOrder == "before_expansion" and saved["mean"] is not None:
    for i in range(len(inputs)):
      body.append(
        f"        v{i} = (v{i} - {saved['mean'][i]!r}) / {saved['scale'][i]!r};")
  for i, term in enumerate(basis.terms):
    body.append(
      f"        double b{i} = {_java_expression(term.expression, values, None)};")
    values[term.name] = f"b{i}"
  columns = [values[name] for name in saved["outputNames"]]
  if basis.scalingOrder == "after_expansion" and saved["mean"] is not None:
    columns = [f"(({name} - {saved['mean'][i]!r}) / {saved['scale'][i]!r})" for
               i, name in enumerate(columns)]
  for i, coef in enumerate(artifact["coefficients"] or []):
    lines.append(f"    private static double score{i}({args}) {{")
    lines.extend(body)
    formula = " + ".join(
      [repr(artifact["intercept"][i])] + [f"({weight!r} * {name})" for
                                          weight, name in zip(coef, columns)])
    lines.extend([f"        return {formula};", "    }"])
  if artifact["kind"] == "classification":
    for label in range(len(artifact["labels"])):
      if artifact["constantClass"] is not None:
        formula = "1.0" if label == artifact["constantClass"] else "0.0"
      elif label not in artifact["classes"]:
        formula = "0.0"
      elif len(artifact["coefficients"]) == 1:
        p = f"(1.0 / (1.0 + Math.exp(-score0({calls}))))"
        formula = p if label == artifact["classes"][1] else f"(1.0 - {p})"
      else:
        idx = artifact["classes"].index(label)
        scores = [f"score{i}({calls})" for i in range(len(artifact["classes"]))]
        maximum = scores[0]
        for score in scores[1:]:
          maximum = f"Math.max({maximum}, {score})"
        denominator = " + ".join(
            f"Math.exp({score} - {maximum})" for score in scores)
        formula = f"Math.exp({scores[idx]} - {maximum}) / ({denominator})"
      lines.extend([f"    public static double output{label}({args}) {{",
                    f"        return {formula};", "    }"])
    lines.append(f"    public static int actionIndex({args}) {{")
    if len(artifact["labels"]) == 2:
      lines.append(
        f"        return output1({calls}) >= {artifact['threshold']!r} ? 1 : 0;")
    else:
      lines.extend(["        int best = 0;",
                    f"        double probability = output0({calls});"])
      for i in range(1, len(artifact["labels"])):
        lines.append(f"        double p{i} = output{i}({calls});")
        lines.append(
          f"        if (p{i} > probability) {{ probability = p{i}; best = {i}; }}")
      lines.append("        return best;")
    lines.append("    }")
  else:
    for i, target in enumerate(artifact["targets"]):
      op = target["transform"] if isinstance(target["transform"], str) else \
      target["transform"]["op"]
      formula = f"score{i}({calls})"
      if op in {"log", "log1p"}:
        formula = f"Math.{'exp' if op == 'log' else 'expm1'}({formula})"
      elif op != "identity":
        raise ValueError("unsupported target inverse for Java")
      bounds = artifact["export"]["bounds"].get(target["name"]) if \
      artifact["export"]["kind"] == "linear" else None
      if bounds:
        formula = f"Math.max({bounds[0]!r}, Math.min({bounds[1]!r}, {formula}))"
      if artifact["export"]["kind"] == "linear" and artifact["export"][
        "rounding"] == "nearest":
        formula = f"Math.floor({formula} + 0.5)"
      elif artifact["export"]["kind"] == "linear" and artifact["export"][
        "rounding"] == "floor":
        formula = f"Math.floor({formula})"
      lines.extend([f"    public static double output{i}({args}) {{",
                    f"        return {formula};", "    }"])
  if artifact["export"]["kind"] == "timing_table":
    count = artifact["featureCount"]
    target = [x["name"] for x in artifact["targets"]].index(
        artifact["decisionOutput"])
    feature_args = ", ".join(f"double x{i}" for i in range(count))
    lines.append(f"    public static int timingIndex({feature_args}) {{")
    lines.extend(["        int best = 0;",
                  "        double bestScore = Double.NEGATIVE_INFINITY;"])
    for i, pair in enumerate(artifact["controlTable"]):
      call = ", ".join(
        [f"x{j}" for j in range(count)] + [repr(float(v)) for v in pair])
      lines.extend([f"        double s{i} = output{target}({call});",
                    f"        if (s{i} > bestScore) {{ bestScore = s{i}; best = {i}; }}"])
    lines.extend(["        return best;", "    }"])
    # Preallocated coupled tuples; callers select one index, never components independently.
    for j in range(len(inputs) - count):
      values = ", ".join(
          repr(float(pair[j])) for pair in artifact["controlTable"])
      lines.extend(
          [f"    private static final double[] CONTROL{j} = {{{values}}};",
           f"    public static double control{j}(int index) {{ return CONTROL{j}[index]; }}"])
  lines.append("}")
  return "\n".join(lines) + "\n"


def participation_java(artifact, artifact_sha256):
  """Byte-preserving facade for the existing frozen participation format."""
  from .logistic_runtime import render_java as legacy_render_java
  return legacy_render_java(artifact, artifact_sha256)


def render_cache_timing(config, class_name="CacheTimingEvaluator"):
  """Export a fixed paired local policy, never the offline search surrogate.

  Primitive methods share the same domain guard and use Java nanosecond rounding.
  The caller supplies the two immutable CacheTimingConfig fallback values.
  """
  import re
  if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", class_name):
    raise ValueError("invalid Java class name")
  width = len(config["parkCoefficients"])
  if width not in (4, 7):
    raise ValueError(
      "timing basis requires four or seven coefficients per output")
  for name, size in (("means", 3), ("scales", 3), ("supportMin", 3),
                     ("supportMax", 3), ("parkCoefficients", width),
                     ("halfLifeCoefficients", width)):
    values = np.asarray(config[name], dtype=float)
    if values.shape != (size,) or not np.isfinite(values).all():
      raise ValueError("invalid timing coefficients/normalization")
  if any(s <= 0 for s in config["scales"]) or any(
      lo < 0 or hi < lo for lo, hi in
      zip(config["supportMin"], config["supportMax"])):
    raise ValueError("invalid timing support/scales")
  if config["supportMax"][0] > 1:
    raise ValueError("contention support exceeds one")
  if not isinstance(config.get("normalizationVersion"), str) or not config[
    "normalizationVersion"].strip():
    raise ValueError("timing normalization must be versioned")
  with np.errstate(over="ignore", invalid="ignore"):
    zmax = np.maximum(
        np.abs((np.asarray(config["supportMin"]) - config["means"]) / config[
          "scales"]),
        np.abs((np.asarray(config["supportMax"]) - config["means"]) / config[
          "scales"]))
    if width == 7:
      zmax = np.r_[
        zmax, zmax[0] * zmax[1], zmax[0] * zmax[2], zmax[1] * zmax[2]]
    for field in ("parkCoefficients", "halfLifeCoefficients"):
      coeff = np.asarray(config[field])
      if not np.isfinite(zmax).all() or not np.isfinite(
          abs(coeff[0]) + np.sum(np.abs(coeff[1:]) * zmax)):
        raise ValueError("nonfinite timing log-time effect")
  lines = [
    "// Unmeasured coefficients remain candidates until scheduler validation.",
    f"public final class {class_name} {{"]
  for prefix in ("park", "halfLife"):
    ref, lo, hi = [config[prefix + key + "Nanos"] for key in
                   ("Reference", "Min", "Max")]
    if any(type(v) is not int or v > 2 ** 63 - 1 for v in (ref, lo, hi)) or \
        ref <= 0 or lo < (0 if prefix == "park" else 1) or not lo <= ref <= hi:
      raise ValueError("invalid timing output bounds")
    coefficients = config[prefix + "Coefficients"]
    guards = ["!Double.isFinite(c)", "!Double.isFinite(p)",
              "!Double.isFinite(b)", "body < 0"]
    guards += [
      f"{name} < {float(config['supportMin'][i])} || {name} > {float(config['supportMax'][i])}"
      for i, name in enumerate(("c", "p", "b"))]
    terms = [repr(float(coefficients[0]))] + [
      f"{float(coefficients[i + 1])} * {name}"
      for i, name in enumerate(("zc", "zp", "zb"))]
    interaction = [] if width == 4 else [
      "        exponent += " + " + ".join(
          f"{float(coefficients[i + 4])} * ({term})"
          for i, term in enumerate(("zc * zp", "zc * zb", "zp * zb"))) + ";"]
    lines += [
      f"    public static long {prefix}Nanos(double c, double p, double body, long fallback) {{",
      "        double b = Math.log1p(body);",
      "        if (" + " || ".join(guards) + ") return fallback;",
      *[
        f"        double z{name} = ({name} - {float(config['means'][i])}) / {float(config['scales'][i])};"
        for i, name in enumerate(("c", "p", "b"))],
      "        double exponent = " + " + ".join(terms) + ";",
      *interaction,
      f"        double bounded = Math.clamp({ref}.0 * Math.exp(exponent), (double) {lo}L, (double) {hi}L);",
      f"        return Math.clamp(Math.round(bounded), {lo}L, {hi}L);",
      "    }"]
  return "\n".join(lines + ["}", ""])
