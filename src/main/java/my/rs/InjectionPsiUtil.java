package my.rs;

import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassObjectAccessExpression;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiLiteralValue;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiType;
import com.intellij.psi.impl.source.PsiImmediateClassType;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AnnotatedMembersSearch;
import com.intellij.util.Query;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import lombok.Value;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class InjectionPsiUtil {

  public static final String MIXIN_ANNOTATION = "net.runelite.api.mixins.Mixin";
  public static final String MIXINS_ANNOTATION = "net.runelite.api.mixins.Mixins";

  public static final String IMPORT_ANNOTATION = "net.runelite.mapping.Import";
  public static final String EXPORT_ANNOTATION = "net.runelite.mapping.Export";


  public static final String COPY_ANNOTATION = "net.runelite.api.mixins.Copy";
  public static final String FIELD_HOOK_ANNOTATION = "net.runelite.api.mixins.FieldHook";
  public static final String METHOD_HOOK_ANNOTATION = "net.runelite.api.mixins.MethodHook";
  public static final String REPLACE_ANNOTATION = "net.runelite.api.mixins.Replace";
  public static final String SHADOW_ANNOTATION = "net.runelite.api.mixins.Shadow";


  public static final String IMPLEMENTS_ANNOTATION = "net.runelite.mapping.Implements";

  public static final List<String> MIXIN_ANNOTATIONS = Arrays.asList(
      COPY_ANNOTATION,
      FIELD_HOOK_ANNOTATION,
      METHOD_HOOK_ANNOTATION,
      REPLACE_ANNOTATION,
      SHADOW_ANNOTATION
  );

  public static final List<String> RELEVANT_ANNOTATIONS = Arrays.asList(
      IMPORT_ANNOTATION,
      EXPORT_ANNOTATION,

      COPY_ANNOTATION,
      FIELD_HOOK_ANNOTATION,
      METHOD_HOOK_ANNOTATION,
      REPLACE_ANNOTATION,
      SHADOW_ANNOTATION
  );


  public static boolean isStatic(PsiMember member) {
    PsiModifierList modifierList = member.getModifierList();
    if (modifierList == null) {
      return false;
    }

    return modifierList.hasModifierProperty(PsiModifier.STATIC);
  }

  public static boolean isRelevantAnnotation(PsiAnnotation annotation) {
    for (String name : RELEVANT_ANNOTATIONS) {
      // Using hasQualifiedName is better for performance than getQualifiedName & comparing ourselves - see javadoc
      if (annotation.hasQualifiedName(name)) {
        return true;
      }
    }

    return false;
  }

  /**
   * Returns the String value of an annotation
   */
  @Nullable
  public static String getAnnotationValue(@NotNull PsiMember element,
      @NotNull String annotationFqn) {
    PsiAnnotation annotation = element.getAnnotation(annotationFqn);
    if (annotation == null) {
      return null;
    }

    PsiAnnotationMemberValue value = annotation.findAttributeValue("value");
    if (!(value instanceof PsiLiteralValue)) {
      return null;
    }

    return (String) ((PsiLiteralValue) value).getValue();
  }

  public static void findAnnotatedElements(Project project,
      Collection<String> annotationQualifiedNames,
      Consumer<AnnotatedElement> consumer) {
    for (String annotationQualifiedName : annotationQualifiedNames) {
      findAnnotatedElements(project, annotationQualifiedName, consumer);
    }
  }

  /**
   * Finds all elements in project annotated by annotationQualifiedName, extracts the value and
   * invokes the consumer with the name and the found element
   */
  public static void findAnnotatedElements(Project project,
      String annotationQualifiedName, Consumer<AnnotatedElement> consumer) {

    JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
    PsiClass annotationClass = facade
        .findClass(annotationQualifiedName, GlobalSearchScope.projectScope(project));

    if (annotationClass == null) {
      return;
    }

    Query<PsiMember> annotatedTargets = AnnotatedMembersSearch
        .search(annotationClass, GlobalSearchScope.projectScope(project));

    for (PsiMember target : annotatedTargets) {
      PsiAnnotation foundAnnotation = target.getAnnotation(annotationQualifiedName);
      if (foundAnnotation == null) {
        continue;
      }

      String annotationValue = getAnnotationValue(target, annotationQualifiedName);
      if (annotationValue == null) {
        continue;
      }

      consumer.accept(new AnnotatedElement(foundAnnotation, annotationValue, target));
    }
  }

  /**
   * For the annotation @Mixin(RSxyz.class) this will return the PsiType for the RSxyz class
   */
  public static PsiClassType getMixinAnnotationValue(PsiAnnotation mixinAnnotation) {
    PsiAnnotationMemberValue value = mixinAnnotation.findAttributeValue("value");
    if (!(value instanceof PsiClassObjectAccessExpression)) {
      return null;
    }

    PsiType targetTypeWrapper = ((PsiClassObjectAccessExpression) value).getType();
    // Class<RSxyz>
    if (!(targetTypeWrapper instanceof PsiClassType)) {
      return null;
    }

    // RSxyz
    return (PsiClassType) ((PsiClassType) targetTypeWrapper).getParameters()[0];
  }

  @Value
  static class AnnotatedElement {

    PsiAnnotation annotation;
    String value;
    PsiMember member;
  }
}
