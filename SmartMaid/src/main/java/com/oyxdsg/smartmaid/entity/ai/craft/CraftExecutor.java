package com.oyxdsg.smartmaid.entity.ai.craft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidActions;
import com.oyxdsg.smartmaid.entity.ai.MaidDebug;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 制作执行器（M3）：女仆消耗背包材料直接合成目标物品（不需要工作台）。
 *
 * <p>核心流程（AI 只说成品，女仆自动找配方凑材料）：</p>
 * <ol>
 *     <li>按成品过滤候选配方（优先用物品自带 {@code DataComponents.RECIPES} 配方 id 列表，
 *         兜底遍历 RecipeManager 全部 crafting 配方）</li>
 *     <li>对每个候选：用 {@link PlacementInfo#slotsToIngredientIndex()} 的 row-major 布局
 *         （按配方真实宽高），从背包抽材料构建 {@link CraftingInput} 并 {@code matches} 验证</li>
 *     <li>匹配成功且 {@code assemble} 产物 = 目标物品 → 消耗背包材料 → 产物进背包</li>
 * </ol>
 *
 * <p>布局按配方真实尺寸（ShapedRecipe.getWidth/getHeight）构建 CraftingInput，
 * 因此 1x1（原木→木板）、1x2（木板→木棍）、2x2（工作台）、3x3（铁镐）都能正确匹配；
 * 不需要工作台方块。</p>
 */
public final class CraftExecutor {

    private CraftExecutor() {
    }

    /** 一次合成的构建结果：CraftingInput + 每背包槽消耗量 + 产物 */
    private record CraftResult(CraftingInput input, int[] consume, ItemStack out) {
    }

    /**
     * 递归合成深度上限（原木→木板→木棍→目标 = 2 层中间物，留裕量）。
     * 超过上限放弃，防止配方链无限展开。
     */
    private static final int MAX_RECURSE_DEPTH = 4;

    /** 递归合成中间物时单次 while 的最大安全次数（防极端情况死循环） */
    private static final int MAX_CRAFT_GUARD = 256;

    /**
     * 尝试合成目标物品（支持递归：背包里没有的直接材料，可由其他配方逐层合成补足）。
     *
     * <p>例如背包没有木棍但有原木时，会自动先合成木板、木棍，再合成目标。</p>
     *
     * <p><b>循环防护</b>：递归带「展开链 visited + 深度上限」，诸如「铁块→铁锭→铁块」
     * 这类互成配方的循环不会无限展开（展开链上出现过的物品直接放弃该分支）。</p>
     *
     * @return 成功返回产物 ItemStack（已进背包）；失败返回 EMPTY
     */
    public static ItemStack craft(SmartMaidEntity maid, ItemStack target) {
        if (maid.level().isClientSide() || !(maid.level() instanceof ServerLevel)) {
            return ItemStack.EMPTY;
        }
        if (!ensureMaterials(maid, target, 1, 0, new HashSet<>())) {
            MaidDebug.log("craft 缺材料(含递归合成): " + target.getItem().getDescriptionId());
            return ItemStack.EMPTY;
        }
        return craftDirect(maid, target);
    }

    /** 单层合成（材料须已齐备，不递归）。 */
    private static ItemStack craftDirect(SmartMaidEntity maid, ItemStack target) {
        if (maid.level().isClientSide() || !(maid.level() instanceof ServerLevel level)) {
            return ItemStack.EMPTY;
        }
        RecipeManager rm = level.recipeAccess();

        // 1. 候选配方（优先 RECIPES component，兜底全量遍历）
        List<RecipeHolder<CraftingRecipe>> candidates = collectCandidates(maid, rm, target);
        if (candidates.isEmpty()) {
            MaidDebug.log("craft 无候选配方: " + target.getItem().getDescriptionId());
            return ItemStack.EMPTY;
        }

        // 2. 逐个用背包材料构建并匹配
        for (RecipeHolder<CraftingRecipe> holder : candidates) {
            CraftResult result = buildInput(maid, holder.value());
            if (result == null) {
                continue; // 材料不足，试下一个配方
            }
            if (!ItemStack.isSameItemSameComponents(result.out(), target)) {
                continue; // 产物不匹配目标，跳过
            }
            // 3. 消耗材料 + 产出进背包
            SimpleContainer inv = maid.getMaidInventory();
            int[] consume = result.consume();
            for (int s = 0; s < consume.length; s++) {
                if (consume[s] > 0 && !inv.getItem(s).isEmpty()) {
                    inv.getItem(s).shrink(consume[s]);
                }
            }
            inv.setChanged();
            maid.swing(InteractionHand.MAIN_HAND);
            ItemStack left = MaidActions.storeToBackpack(maid, result.out());
            if (!left.isEmpty()) {
                maid.spawnAtLocation(level, left); // 背包满则掉地上
            }
            MaidDebug.log("craft 成功: " + result.out().getItem().getDescriptionId()
                    + " x" + result.out().getCount());
            return result.out();
        }
        MaidDebug.log("craft 缺材料: " + target.getItem().getDescriptionId());
        return ItemStack.EMPTY;
    }

    /**
     * 确保背包能凑齐「合成 count 个 target」所需的全部材料；缺的直接材料
     * 会递归合成进背包（如合成木板补木板、合成木棍补木棍）。
     *
     * <p><b>循环防护</b>：{@code visited} 记录当前展开链上的物品，
     * 再次出现（铁块→铁锭→铁块）即视为该分支不可行。</p>
     *
     * @return 材料齐备可执行真合成；无法凑齐返回 false
     */
    private static boolean ensureMaterials(SmartMaidEntity maid, ItemStack target, int count,
                                           int depth, Set<Item> visited) {
        if (depth > MAX_RECURSE_DEPTH) {
            return false;
        }
        if (maid.level().isClientSide() || !(maid.level() instanceof ServerLevel level)) {
            return false;
        }
        Item tItem = target.getItem();
        if (visited.contains(tItem)) {
            return false; // 配方循环（如铁块→铁锭→铁块）
        }
        List<RecipeHolder<CraftingRecipe>> candidates =
                collectCandidates(maid, level.recipeAccess(), target);
        if (candidates.isEmpty()) {
            return false;
        }
        Set<Item> next = new HashSet<>(visited);
        next.add(tItem);
        for (RecipeHolder<CraftingRecipe> holder : candidates) {
            CraftingRecipe recipe = holder.value();
            // 全量扫描路径必须验证产物 = target，否则会选中「材料可满足但产出不对」的配方
            if (!produces(maid, recipe, target)) {
                continue;
            }
            int perCraft = outputCount(recipe);
            int crafts = (int) Math.ceil(count / (double) Math.max(1, perCraft));
            IngredientNeeds in = ingredientNeeds(recipe);
            int[] need = in.need();
            List<Ingredient> ingredients = in.ingredients();
            boolean ok = true;
            for (int idx = 0; idx < need.length; idx++) {
                if (need[idx] <= 0) {
                    continue;
                }
                Ingredient ing = ingredients.get(idx);
                if (!ensureIngredient(maid, ing, need[idx] * crafts, depth + 1, next)) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return true; // 该配方材料已齐，可执行真合成
            }
        }
        return false;
    }

    /**
     * 确保背包里匹配 {@code ing} 的物品总数 ≥ {@code need}；不足时递归合成
     * 候选项（取第一个可合成的）并放进背包，直到数量达标。
     */
    private static boolean ensureIngredient(SmartMaidEntity maid, Ingredient ing, int need,
                                            int depth, Set<Item> visited) {
        SimpleContainer inv = maid.getMaidInventory();
        if (countMatching(inv, ing) >= need) {
            return true;
        }
        if (depth > MAX_RECURSE_DEPTH) {
            return false;
        }
        List<Holder<Item>> candidates = new ArrayList<>();
        try {
            ing.items().limit(MAX_OPTIONS).forEach(candidates::add);
        } catch (Throwable ignored) {
        }
        for (Holder<Item> holder : candidates) {
            Item item = holder.value();
            if (visited.contains(item)) {
                continue;
            }
            int missing = need - countMatching(inv, ing);
            if (missing <= 0) {
                return true;
            }
            if (!ensureMaterials(maid, new ItemStack(item), missing, depth + 1, visited)) {
                continue; // 这个候选项无法递归合成，试下一个
            }
            // 真正合成候选项直到匹配量够
            int guard = 0;
            while (countMatching(inv, ing) < need) {
                if (++guard > MAX_CRAFT_GUARD) {
                    break;
                }
                ItemStack out = craftDirect(maid, new ItemStack(item, 1));
                if (out.isEmpty()) {
                    break;
                }
            }
            if (countMatching(inv, ing) >= need) {
                return true;
            }
        }
        return false;
    }

    /** 配方材料需求：去重后的 ingredient 列表 + 每种在 3x3 布局中出现次数。 */
    private record IngredientNeeds(List<Ingredient> ingredients, int[] need) {
    }

    /**
     * 聚合配方每种材料各需要几个。placement 的 {@code ingredients()} 是「每格展开」列表
     * （铁镐 = [铁锭,铁锭,铁锭,木棍,木棍]），必须按相同材料聚合 → [3, 2]，否则
     * need 会被低估（铁镐 each=1），递归与干跑判断都会失真。
     */
    private static IngredientNeeds ingredientNeeds(CraftingRecipe recipe) {
        List<Ingredient> expanded = recipe.placementInfo().ingredients();
        List<Ingredient> uniq = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (Ingredient ing : expanded) {
            if (ing.isEmpty()) {
                continue;
            }
            int found = -1;
            for (int u = 0; u < uniq.size(); u++) {
                if (sameIngredient(uniq.get(u), ing)) {
                    found = u;
                    break;
                }
            }
            if (found < 0) {
                uniq.add(ing);
                counts.add(1);
            } else {
                counts.set(found, counts.get(found) + 1);
            }
        }
        int[] need = new int[counts.size()];
        for (int i = 0; i < need.length; i++) {
            need[i] = counts.get(i);
        }
        return new IngredientNeeds(uniq, need);
    }

    /** 两个 ingredient 是否等价（候选物品集合相同）。 */
    private static boolean sameIngredient(Ingredient a, Ingredient b) {
        if (a == b) {
            return true;
        }
        try {
            Set<Item> sa = new HashSet<>();
            Set<Item> sb = new HashSet<>();
            a.items().forEach(h -> sa.add(h.value()));
            b.items().forEach(h -> sb.add(h.value()));
            return sa.size() == sb.size() && sa.equals(sb);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 收集候选配方（产物可能是 target 的 crafting 配方） */
    private static List<RecipeHolder<CraftingRecipe>> collectCandidates(SmartMaidEntity maid,
                                                                        RecipeManager rm, ItemStack target) {
        List<RecipeHolder<CraftingRecipe>> candidates = new ArrayList<>();
        List<ResourceKey<Recipe<?>>> keys = target.get(DataComponents.RECIPES);
        if (keys != null && !keys.isEmpty()) {
            for (ResourceKey<Recipe<?>> key : keys) {
                rm.byKey(key).ifPresent(holder -> {
                    if (holder.value() instanceof CraftingRecipe cr) {
                        // byKey 返回 RecipeHolder<?>，强转目标类型（泛型擦除安全）
                        candidates.add((RecipeHolder<CraftingRecipe>) (RecipeHolder<?>) holder);
                    }
                });
            }
            return candidates;
        }
        for (RecipeHolder<?> holder : rm.getRecipes()) {
            if (holder.value().getType() == RecipeType.CRAFTING && holder.value() instanceof CraftingRecipe cr) {
                candidates.add((RecipeHolder<CraftingRecipe>) (RecipeHolder<?>) holder);
            }
        }
        return candidates;
    }

    /**
     * 用背包材料按配方 3x3 布局构建 CraftingInput；材料不足或布局不匹配返回 null。
     * 匹配成功后调用 {@code assemble} 得到产物（仅用于产物校验，不真正消耗）。
     */
    private static CraftResult buildInput(SmartMaidEntity maid, CraftingRecipe recipe) {
        PlacementInfo info = recipe.placementInfo();
        IntList slotToIng = info.slotsToIngredientIndex();
        List<Ingredient> ingredients = info.ingredients();
        SimpleContainer inv = maid.getMaidInventory();

        // 配方真实尺寸：ShapedRecipe 有明确宽高（2x2 工作台 / 3x3 铁镐 / 1x2 木棍），
        // ShapelessRecipe 位置无关按 3x3 兜底。slotToIng 是 row-major 的 width*height
        // 布局（空位 -1），必须按真实宽高建 CraftingInput —— MC 26.2 的
        // ShapedRecipePattern.matches 要求 input 尺寸与配方尺寸严格相等，硬编码 3x3
        // 会让 2x2 / 1x2 / 1x1 配方永远 matches 失败（见 DEVELOPMENT_ISSUES「工作台」）。
        int width = recipeWidth(recipe);
        int height = recipeHeight(recipe);
        ItemStack[] grid = new ItemStack[width * height];
        Arrays.fill(grid, ItemStack.EMPTY);
        int[] consume = new int[inv.getContainerSize()];

        int size = Math.min(slotToIng.size(), width * height);
        for (int i = 0; i < size; i++) {
            int ingIdx = slotToIng.getInt(i);
            if (ingIdx < 0 || ingIdx >= ingredients.size()) {
                continue;
            }
            Ingredient ingredient = ingredients.get(ingIdx);
            if (ingredient.isEmpty()) {
                continue;
            }
            int slot = findMatchingSlot(inv, ingredient, consume);
            if (slot < 0) {
                return null; // 缺材料
            }
            grid[i] = inv.getItem(slot).copy();
            grid[i].setCount(1);
            consume[slot]++;
        }

        CraftingInput input = CraftingInput.of(width, height, Arrays.asList(grid));
        if (!recipe.matches(input, maid.level())) {
            return null;
        }
        return new CraftResult(input, consume, recipe.assemble(input));
    }

    /** 找匹配 ingredient 且还有剩余量的背包槽（0-40） */
    private static int findMatchingSlot(SimpleContainer inv, Ingredient ingredient, int[] consume) {
        for (int s = 0; s < inv.getContainerSize(); s++) {
            ItemStack stack = inv.getItem(s);
            if (!stack.isEmpty() && ingredient.test(stack) && stack.getCount() > consume[s]) {
                return s;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- 干跑查询

    /** ingredient 候选项最多回报几个（tag 类 ingredient 可能展开成上百项） */
    private static final int MAX_OPTIONS = 16;

    /**
     * 干跑查询（dry-run）：目标物品能不能合成、需要什么材料、女仆现有多少。
     *
     * <p><b>不消耗任何材料</b>，供桌宠在自动执行前判断、并向用户解释「差什么」。</p>
     *
     * <p>返回结构（材料不足时 {@code craftable=false}，逐项给出 need/have/ok）：</p>
     * <pre>{@code
     * {"found":true,"craftable":false,"need":1,"crafts":1,"out_per_craft":1,
     *  "ingredients":[{"options":["minecraft:oak_planks",...],"need":3,"have":5,"ok":true},
     *                 {"options":["minecraft:stick"],"need":2,"have":0,"ok":false}]}
     * }</pre>
     *
     * <p>候选配方优先取物品自带的 {@code DataComponents.RECIPES}（此时产物必然匹配，
     * 无需校验）；兜底全量扫描时用一个「填满候选项的 3x3 假网格」验证产物。</p>
     */
    public static JsonObject check(SmartMaidEntity maid, ItemStack target, int count) {
        JsonObject out = new JsonObject();
        out.addProperty("found", false);
        out.addProperty("craftable", false);
        out.add("ingredients", new JsonArray());
        if (target == null || target.isEmpty()) {
            return out;
        }
        if (maid.level().isClientSide() || !(maid.level() instanceof ServerLevel level)) {
            return out;
        }
        int want = Math.max(1, count);
        out.addProperty("need", want);

        RecipeManager rm = level.recipeAccess();
        List<RecipeHolder<CraftingRecipe>> candidates = new ArrayList<>();
        boolean trusted = false;
        List<ResourceKey<Recipe<?>>> keys = target.get(DataComponents.RECIPES);
        if (keys != null && !keys.isEmpty()) {
            trusted = true; // 物品自带的配方 id：产物必然是该物品
            for (ResourceKey<Recipe<?>> key : keys) {
                rm.byKey(key).ifPresent(holder -> {
                    if (holder.value() instanceof CraftingRecipe) {
                        candidates.add((RecipeHolder<CraftingRecipe>) (RecipeHolder<?>) holder);
                    }
                });
            }
        } else {
            for (RecipeHolder<?> holder : rm.getRecipes()) {
                if (holder.value().getType() == RecipeType.CRAFTING
                        && holder.value() instanceof CraftingRecipe) {
                    candidates.add((RecipeHolder<CraftingRecipe>) (RecipeHolder<?>) holder);
                }
            }
        }
        if (candidates.isEmpty()) {
            return out;
        }

        JsonArray best = null;
        int bestMissing = Integer.MAX_VALUE;
        int bestPerCraft = 1;
        for (RecipeHolder<CraftingRecipe> holder : candidates) {
            CraftingRecipe recipe = holder.value();
            if (!trusted && !produces(maid, recipe, target)) {
                continue;
            }
            int perCraft = outputCount(recipe);
            JsonArray desc = describe(maid, recipe, want, perCraft);
            int missing = countMissing(desc);
            if (best == null || missing < bestMissing) {
                best = desc;
                bestMissing = missing;
                bestPerCraft = perCraft;
            }
            if (missing == 0 && trusted) {
                break; // 已可合成，无需再比
            }
        }
        if (best == null) {
            return out;
        }
        out.addProperty("found", true);
        out.addProperty("craftable", bestMissing == 0);
        out.addProperty("out_per_craft", bestPerCraft);
        out.addProperty("crafts",
                (int) Math.ceil(want / (double) Math.max(1, bestPerCraft)));
        out.add("ingredients", best);
        return out;
    }

    /**
     * 逐项列出「需要多少 / 现有多少 / 够不够」。背包缺的直接材料若可递归合成
     * 补足则 {@code ok=true}，并附 {@code via_craft=true} 与 {@code craft_plan}
     * （需要先合成的中间物品列表），供桌宠向用户解释。
     */
    private static JsonArray describe(SmartMaidEntity maid, CraftingRecipe recipe,
                                      int want, int perCraft) {
        SimpleContainer inv = maid.getMaidInventory();

        IngredientNeeds in = ingredientNeeds(recipe);
        List<Ingredient> ingredients = in.ingredients();
        int[] perCraftNeed = in.need();
        int crafts = (int) Math.ceil(want / (double) Math.max(1, perCraft));

        JsonArray arr = new JsonArray();
        for (int idx = 0; idx < ingredients.size(); idx++) {
            if (perCraftNeed[idx] <= 0) {
                continue;
            }
            Ingredient ing = ingredients.get(idx);
            JsonObject item = new JsonObject();
            JsonArray options = new JsonArray();
            try {
                ing.items().limit(MAX_OPTIONS).forEach(holder ->
                        holder.unwrapKey().ifPresent(
                                key -> options.add(key.identifier().toString())));
            } catch (Throwable ignored) {
                // 取不到候选项不影响 need/have 判断
            }
            item.add("options", options);
            int need = perCraftNeed[idx] * crafts;
            int have = countMatching(inv, ing);
            item.addProperty("need", need);
            item.addProperty("have", have);
            boolean ok = have >= need;
            if (!ok) {
                // 递归干跑：背包缺的直接材料能否由合成补足（含循环防护）
                List<String> plan = resolveMissing(maid, ing, need - have, 0, new HashSet<>());
                if (plan != null) {
                    ok = true;
                    item.addProperty("via_craft", true);
                    JsonArray via = new JsonArray();
                    plan.forEach(via::add);
                    item.add("craft_plan", via);
                }
            }
            item.addProperty("ok", ok);
            arr.add(item);
        }
        return arr;
    }

    /**
     * 干跑：ingredient 缺 {@code missing} 个，能否通过逐层合成补齐（不消耗材料）。
     *
     * @return 可补齐时返回「需要先合成的中间物品」列表（含缺口量，如
     *         {@code ["minecraft:stick×2","minecraft:oak_planks×2"]}）；否则返回 null
     */
    private static List<String> resolveMissing(SmartMaidEntity maid, Ingredient ing, int missing,
                                               int depth, Set<Item> visited) {
        if (depth > MAX_RECURSE_DEPTH) {
            return null;
        }
        List<Holder<Item>> candidates = new ArrayList<>();
        try {
            ing.items().limit(MAX_OPTIONS).forEach(candidates::add);
        } catch (Throwable ignored) {
        }
        for (Holder<Item> holder : candidates) {
            Item item = holder.value();
            if (visited.contains(item)) {
                continue;
            }
            List<String> r = resolveItem(maid, item, missing, depth + 1, visited);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    /**
     * 干跑：合成 {@code count} 个 item 的可行性解析（不消耗材料）。
     *
     * <p>递归检查该物品配方的每种材料：背包直接够的跳过；不够的递归
     * {@link #resolveMissing}。全部可满足则返回「该物品自身 + 子链需要先合成的
     * 中间物」列表；任一分支无解 / 配方循环 / 超深度返回 null。</p>
     */
    private static List<String> resolveItem(SmartMaidEntity maid, Item item, int count,
                                            int depth, Set<Item> visited) {
        if (depth > MAX_RECURSE_DEPTH || visited.contains(item)) {
            return null;
        }
        if (maid.level().isClientSide() || !(maid.level() instanceof ServerLevel level)) {
            return null;
        }
        RecipeManager rm = level.recipeAccess();
        List<RecipeHolder<CraftingRecipe>> candidates =
                collectCandidates(maid, rm, new ItemStack(item));
        if (candidates.isEmpty()) {
            return null;
        }
        Set<Item> next = new HashSet<>(visited);
        next.add(item);
        SimpleContainer inv = maid.getMaidInventory();
        for (RecipeHolder<CraftingRecipe> holder : candidates) {
            CraftingRecipe recipe = holder.value();
            // 全量扫描路径必须验证产物 = item，否则会选中「材料可满足但产出不对」的配方
            if (!produces(maid, recipe, new ItemStack(item))) {
                continue;
            }
            int perCraft = outputCount(recipe);
            int crafts = (int) Math.ceil(count / (double) Math.max(1, perCraft));
            IngredientNeeds in = ingredientNeeds(recipe);
            int[] need = in.need();
            List<Ingredient> ingredients = in.ingredients();
            List<String> subInputs = new ArrayList<>();
            boolean ok = true;
            for (int idx = 0; idx < need.length; idx++) {
                if (need[idx] <= 0) {
                    continue;
                }
                Ingredient ing = ingredients.get(idx);
                int have = countMatching(inv, ing);
                int needN = need[idx] * crafts;
                if (have >= needN) {
                    continue; // 背包直接够，不产生额外中间物
                }
                List<String> sub = resolveMissing(maid, ing, needN - have, depth + 1, next);
                if (sub == null) {
                    ok = false;
                    break;
                }
                subInputs.addAll(sub);
            }
            if (ok) {
                List<String> result = new ArrayList<>();
                String id = BuiltInRegistries.ITEM.getKey(item).toString();
                result.add(id + "\u00d7" + count); // 该物品自身需要被合成
                result.addAll(subInputs);
                return result;
            }
        }
        return null;
    }

    /** 统计描述里「不够」的条目数。 */
    private static int countMissing(JsonArray desc) {
        int n = 0;
        for (int i = 0; i < desc.size(); i++) {
            JsonObject o = desc.get(i).getAsJsonObject();
            if (!o.has("ok") || !o.get("ok").getAsBoolean()) {
                n++;
            }
        }
        return n;
    }

    /** 背包里匹配该 ingredient 的物品总数。 */
    private static int countMatching(SimpleContainer inv, Ingredient ing) {
        int total = 0;
        for (int s = 0; s < inv.getContainerSize(); s++) {
            ItemStack stack = inv.getItem(s);
            if (!stack.isEmpty() && ing.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** 一次合成产出几个（用候选项填满的假网格 assemble 得到）。 */
    private static int outputCount(CraftingRecipe recipe) {
        CraftingInput input = filledInput(recipe);
        if (input == null) {
            return 1;
        }
        try {
            return Math.max(1, recipe.assemble(input).getCount());
        } catch (Throwable t) {
            return 1;
        }
    }

    /** 全量扫描候选时，校验这条配方确实产出 target。 */
    private static boolean produces(SmartMaidEntity maid, CraftingRecipe recipe, ItemStack target) {
        CraftingInput input = filledInput(recipe);
        if (input == null) {
            return false;
        }
        try {
            if (!recipe.matches(input, maid.level())) {
                return false;
            }
            return ItemStack.isSameItemSameComponents(recipe.assemble(input), target);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 用每个 ingredient 的第一个候选项搭一个假网格（按配方真实宽高，只看形状，不看数量）。 */
    private static CraftingInput filledInput(CraftingRecipe recipe) {
        PlacementInfo info = recipe.placementInfo();
        IntList slotToIng = info.slotsToIngredientIndex();
        List<Ingredient> ingredients = info.ingredients();
        int width = recipeWidth(recipe);
        int height = recipeHeight(recipe);
        ItemStack[] grid = new ItemStack[width * height];
        Arrays.fill(grid, ItemStack.EMPTY);
        int size = Math.min(slotToIng.size(), width * height);
        for (int i = 0; i < size; i++) {
            int idx = slotToIng.getInt(i);
            if (idx < 0 || idx >= ingredients.size()) {
                continue;
            }
            Ingredient ing = ingredients.get(idx);
            if (ing.isEmpty()) {
                continue;
            }
            try {
                grid[i] = ing.items().findFirst()
                        .map(holder -> new ItemStack(holder, 1))
                        .orElse(ItemStack.EMPTY);
            } catch (Throwable ignored) {
                // 保持 EMPTY
            }
        }
        return CraftingInput.of(width, height, Arrays.asList(grid));
    }

    /** 配方行宽。ShapedRecipe 有明确宽高；ShapelessRecipe 位置无关，按 3 兜底。 */
    private static int recipeWidth(CraftingRecipe recipe) {
        if (recipe instanceof ShapedRecipe shaped) {
            return Math.max(1, shaped.getWidth());
        }
        return 3;
    }

    /** 配方行高。ShapedRecipe 有明确宽高；ShapelessRecipe 位置无关，按 3 兜底。 */
    private static int recipeHeight(CraftingRecipe recipe) {
        if (recipe instanceof ShapedRecipe shaped) {
            return Math.max(1, shaped.getHeight());
        }
        return 3;
    }
}
