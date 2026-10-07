package com.oyxdsg.smartmaid.compat;

/**
 * 动作引擎（mocha / molang 表达式求值器）的**版本差异隔离层** —— 26.2 实现。
 *
 * <p>PAL 在 1.2.7 里换掉了内嵌的表达式引擎：包名从 {@code team.unnamed.mocha}
 * （jar-in-jar 的 mochafloats 5.0.0）换成 {@code org.redlance.mocha.runtime}
 * （jar-in-jar 的 runtime/parser/lexer 6.0.1），类名 {@code MochaEngine} 也变成
 * {@link org.redlance.mocha.runtime.MolangInterpreter}。工厂方法 {@code create(T)}
 * 语义不变，所以只剩包名 + 类名两处差异。</p>
 *
 * <p>共享代码 {@code MaidAnimManager} **不引用任何一侧的类型**，只写
 * {@code c -> MaidAnimCompat.createEngine(c)}：lambda 的返回类型由泛型方法按实参
 * 推导，再交给当版本的 {@code HumanoidAnimationController} 构造器匹配 —— 因此
 * 「包名换了」这件事被完全挡在这个文件里。</p>
 *
 * <p>依赖来源：{@code libs/player_animation_library-1.2.6.jar}（编译）+ 其 jar-in-jar
 * 提供的运行时类；编译期另需 {@code libs/mochafloats-5.0.0.jar}。</p>
 */
public final class MaidAnimCompat {
    private MaidAnimCompat() {
    }

    /**
     * 为动画控制器创建一个表达式引擎。
     *
     * @param entity 引擎绑定的持有者对象（PAL 传入 {@code AnimationController}）
     * @param <T>    持有者类型
     * @return 新建的 mocha 引擎
     */
    public static <T> team.unnamed.mocha.MochaEngine<T> createEngine(T entity) {
        return team.unnamed.mocha.MochaEngine.create(entity);
    }
}
