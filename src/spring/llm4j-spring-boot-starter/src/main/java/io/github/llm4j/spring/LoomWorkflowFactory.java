package io.github.llm4j.spring;

import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.bind.Bindgen;
import io.github.llm4j.loom.host.LoomHost;
import io.github.llm4j.loom.host.RunOutcome;
import io.github.llm4j.loom.host.RunRequest;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.SmartFactoryBean;

/**
 * The bean behind a workflow interface: a generated facade is built with its {@code of(LoomHost)};
 * a plain {@link LoomWorkflows} interface becomes a proxy that runs the workflow its method is
 * named after. Either way the interface is checked against the script when the bean is created,
 * which is at start.
 */
final class LoomWorkflowFactory implements SmartFactoryBean<Object>, BeanFactoryAware {

    private final Class<?> type;
    private BeanFactory beans;

    LoomWorkflowFactory(Class<?> type) {
        this.type = type;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beans = beanFactory;
    }

    @Override
    public Class<?> getObjectType() {
        return type;
    }

    /** Checked when the application starts, not when the bean is first used. */
    @Override
    public boolean isEagerInit() {
        return true;
    }

    @Override
    public Object getObject() throws Exception {
        LoomRuntime runtime = beans.getBean(LoomRuntime.class);
        LoomHost host = runtime.host();
        if (!type.isInterface())
            throw new IllegalStateException(type.getName() + " is not an interface.");
        Method of = null;
        try {
            of = type.getMethod("of", LoomHost.class);
        } catch (NoSuchMethodException e) {
            // a plain interface: proxy it
        }
        if (of != null && Modifier.isStatic(of.getModifiers())) return of.invoke(null, host);
        return proxy(host);
    }

    private Object proxy(LoomHost host) {
        Map<Method, WorkflowDef> bound = validate(host);
        return Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[] {type},
                (self, method, args) -> {
                    if (method.getDeclaringClass() == Object.class)
                        return switch (method.getName()) {
                            case "toString" -> "LoomWorkflows(" + type.getSimpleName() + ")";
                            case "hashCode" -> System.identityHashCode(self);
                            default -> self == args[0];
                        };
                    if (method.isDefault())
                        return java.lang.invoke.MethodHandles.privateLookupIn(
                                        type, java.lang.invoke.MethodHandles.lookup())
                                .unreflectSpecial(method, type)
                                .bindTo(self)
                                .invokeWithArguments(args == null ? new Object[0] : args);
                    WorkflowDef w = bound.get(method);
                    Map<String, String> inputs = new LinkedHashMap<>();
                    for (int i = 0; i < w.getParameters().size(); i++)
                        if (args[i] != null) inputs.put(w.getParameters().get(i), (String) args[i]);
                    RunOutcome out = host.run(RunRequest.of(w.getName(), inputs));
                    return out;
                });
    }

    /** Every abstract method must be a workflow, with its parameters, returning a RunOutcome. */
    private Map<Method, WorkflowDef> validate(LoomHost host) {
        List<WorkflowDef> workflows = host.script().getWorkflows();
        Map<Method, WorkflowDef> out = new LinkedHashMap<>();
        for (Method m : type.getMethods()) {
            if (m.isDefault() || Modifier.isStatic(m.getModifiers())) continue;
            WorkflowDef w =
                    workflows.stream()
                            .filter(x -> Bindgen.methodName(x.getName()).equals(m.getName()))
                            .findFirst()
                            .orElse(null);
            if (w == null)
                throw new IllegalStateException(
                        type.getSimpleName()
                                + "."
                                + m.getName()
                                + " matches no workflow in the script"
                                + nearest(m.getName(), workflows)
                                + ". The workflows are "
                                + workflows.stream()
                                        .map(x -> Bindgen.methodName(x.getName()))
                                        .toList()
                                + ".");
            if (m.getReturnType() != RunOutcome.class)
                throw new IllegalStateException(
                        type.getSimpleName()
                                + "."
                                + m.getName()
                                + " returns "
                                + m.getReturnType().getSimpleName()
                                + "; a workflow method returns RunOutcome (generate a facade with weave bindgen for typed results).");
            boolean texts = true;
            for (Class<?> p : m.getParameterTypes()) texts &= p == String.class;
            if (!texts || m.getParameterCount() != w.getParameters().size())
                throw new IllegalStateException(
                        type.getSimpleName()
                                + "."
                                + m.getName()
                                + " must take one String for each of the workflow's parameters, "
                                + w.getParameters()
                                + ", in that order.");
            out.put(m, w);
        }
        return out;
    }

    private static String nearest(String name, List<WorkflowDef> workflows) {
        String best = null;
        int bestD = Integer.MAX_VALUE;
        List<String> names = new ArrayList<>();
        workflows.forEach(w -> names.add(Bindgen.methodName(w.getName())));
        for (String n : names) {
            int d = distance(name.toLowerCase(), n.toLowerCase());
            if (d < bestD) {
                bestD = d;
                best = n;
            }
        }
        return best != null && bestD <= Math.max(3, name.length() / 2)
                ? " (did you mean " + best + "?)"
                : "";
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int[] cur = new int[b.length() + 1];
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++)
                cur[j] =
                        Math.min(
                                Math.min(cur[j - 1] + 1, prev[j] + 1),
                                prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            prev = cur;
        }
        return prev[b.length()];
    }
}
