package com.oyxdsg.smartmaid.compat;

/**
 * 动作引擎（mocha / molang 表达式求值器）的**版本差异隔离层** —— 26.3 实现。
 *
 * <p>PAL 1.2.7 把内嵌表达式引擎从 {@code team.unnamed.mocha.MochaEngine}
 * （mochafloats 5.0.0）换成了 {@code org.redlance.mocha.runtime.MolangInterpreter}
 * （runtime/parser/lexer 6.0.1）。工厂方法 {@code create(T)} 与旧版一一对应，
 * 所以移植只需改包名 + 类名。</p>
 *
 * <p>编译期依赖：{@code libs/mc26.3/player_animation_library-1.2.7.jar}，
 * 以及从它 jar-in-jar 里抽出的 {@code mocha-{runtime,parser,lexer}-6.0.1.jar}
 * （运行时这三份由 PAL 自己的 jar-in-jar 提供，**不随本模组打包**）。</p>
 */
public final class MaidAnimCompat {
    private MaidAnimCompat() {
    }

    /**
     * 为动画控制器创建一个表达式引擎。
     *
     * @param entity 引擎绑定的持有者对象（PAL 传入 {@code AnimationController}）
     * @param <T>    持有者类型
     * @return 新建的 molang 解释器
     */
    public static <T> org.redlance.mocha.runtime.MolangInterpreter<T> createEngine(T entity) {
        return org.redlance.mocha.runtime.MolangInterpreter.create(entity);
    }
}
